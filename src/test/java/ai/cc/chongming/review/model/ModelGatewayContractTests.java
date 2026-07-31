package ai.cc.chongming.review.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.config.ModelGatewayProperties;
import ai.cc.chongming.review.config.ModelProfilesProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException.Code;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.model.CommercialModelGateway;
import ai.cc.chongming.review.infrastructure.model.ModelCallAuditService;
import ai.cc.chongming.review.infrastructure.model.ModelProfileRegistry;
import ai.cc.chongming.review.infrastructure.model.ModelProviderClient;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests deterministic commercial-gateway behavior without contacting a real model provider.
 *
 * @author wangli
 */
class ModelGatewayContractTests {

    @Test
    void retriesOnlyTransientFailuresAndAuditsHashesInsteadOfPrompts() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ModelProviderClient.ProviderRequest> providerRequest = new AtomicReference<>();
        ModelProviderClient provider = request -> {
            providerRequest.set(request);
            if (calls.incrementAndGet() == 1) {
                throw new ModelGatewayException(Code.MODEL_RATE_LIMITED, "transient");
            }
            return new ModelProviderClient.ProviderResponse(
                    "response-1",
                    "{\"tasks\":[]}",
                    new ModelGateway.Usage(11, 7, 18),
                    ModelGateway.FinishReason.STOP);
        };
        ModelCallAuditService audit = new ModelCallAuditService();
        CommercialModelGateway gateway = new CommercialModelGateway(
                enabledProperties(), profileRegistry(1), provider, audit);

        ModelGateway.ModelResponse response = gateway.generate(request(), IntakeCancellation.neverCancelled()).block();

        assertThat(response.attempts()).isEqualTo(2);
        assertThat(calls).hasValue(2);
        assertThat(providerRequest.get().apiKey()).isEqualTo("test-key");
        assertThat(audit.findByReview(responseRequestReviewId())).singleElement().satisfies(entry -> {
            assertThat(entry.inputHash()).hasSize(64);
            assertThat(entry.outputHash()).hasSize(64);
            assertThat(entry.failureCode()).isNull();
        });
    }

    @Test
    void rejectsDisabledOrCancelledCallsWithoutInvokingTheProvider() {
        ModelProviderClient provider = request -> {
            throw new AssertionError("Provider must not be invoked");
        };
        CommercialModelGateway disabled = new CommercialModelGateway(
                new ModelGatewayProperties(false, null, "placeholder", "test-key", false),
                profileRegistry(0),
                provider,
                new ModelCallAuditService());

        assertThatThrownBy(() -> disabled.generate(request(), IntakeCancellation.neverCancelled()).block())
                .isInstanceOf(ModelGatewayException.class)
                .extracting(error -> ((ModelGatewayException) error).code())
                .isEqualTo(Code.MODEL_GATEWAY_DISABLED);
        assertThatThrownBy(() -> new CommercialModelGateway(
                        enabledProperties(), profileRegistry(0), provider, new ModelCallAuditService())
                .generate(request(), () -> true)
                .block())
                .isInstanceOf(ModelGatewayException.class)
                .extracting(error -> ((ModelGatewayException) error).code())
                .isEqualTo(Code.MODEL_CANCELLED);
    }

    @Test
    void switchesToConfiguredFallbackProfileAfterPrimaryTimeout() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<String>> invokedProfiles = new AtomicReference<>(new java.util.ArrayList<>());
        ModelProviderClient provider = providerRequest -> {
            invokedProfiles.get().add(providerRequest.profile().profileId());
            if (calls.incrementAndGet() == 1) {
                throw new ModelGatewayException(Code.MODEL_CALL_TIMEOUT, "primary timed out");
            }
            return new ModelProviderClient.ProviderResponse(
                    "fallback-response",
                    "{\"summary\":\"fallback\"}",
                    new ModelGateway.Usage(5, 3, 8),
                    ModelGateway.FinishReason.STOP);
        };
        ModelCallAuditService audit = new ModelCallAuditService();
        CommercialModelGateway gateway = new CommercialModelGateway(
                enabledProperties(), fallbackProfileRegistry(), provider, audit);

        ModelGateway.ModelResponse response = gateway.generate(scoutRequest(), IntakeCancellation.neverCancelled()).block();

        assertThat(response.modelName()).isEqualTo("fallback-model");
        assertThat(response.attempts()).isEqualTo(2);
        assertThat(invokedProfiles.get()).containsExactly("scout", "scout-fallback");
        assertThat(audit.findByReview(responseRequestReviewId())).satisfiesExactly(
                entry -> {
                    assertThat(entry.profileId()).isEqualTo("scout");
                    assertThat(entry.failureCode()).isEqualTo(Code.MODEL_CALL_TIMEOUT.name());
                    assertThat(entry.attempts()).isEqualTo(1);
                },
                entry -> {
                    assertThat(entry.profileId()).isEqualTo("scout-fallback");
                    assertThat(entry.failureCode()).isNull();
                    assertThat(entry.attempts()).isEqualTo(2);
                });
    }

    @Test
    void doesNotSwitchToFallbackAfterRejectedRequest() {
        AtomicInteger calls = new AtomicInteger();
        ModelProviderClient provider = providerRequest -> {
            calls.incrementAndGet();
            throw new ModelGatewayException(Code.MODEL_REQUEST_REJECTED, "invalid model configuration");
        };
        CommercialModelGateway gateway = new CommercialModelGateway(
                enabledProperties(), fallbackProfileRegistry(), provider, new ModelCallAuditService());

        assertThatThrownBy(() -> gateway.generate(scoutRequest(), IntakeCancellation.neverCancelled()).block())
                .isInstanceOf(ModelGatewayException.class)
                .extracting(error -> ((ModelGatewayException) error).code())
                .isEqualTo(Code.MODEL_REQUEST_REJECTED);
        assertThat(calls).hasValue(1);
    }

    private ModelGatewayProperties enabledProperties() {
        return new ModelGatewayProperties(true, URI.create("https://example.invalid/v1"), "placeholder", "test-key", false);
    }

    private ModelProfileRegistry profileRegistry(int maxRetries) {
        return new ModelProfileRegistry(new ModelProfilesProperties(Map.of(
                "role-reviewer",
                new ModelProfilesProperties.ProfileDefinition(
                        "openai-compatible",
                        "test-model",
                        0.2d,
                        Duration.ofSeconds(1),
                        256,
                        new ModelProfilesProperties.RetryDefinition(maxRetries, Duration.ZERO),
                        null))));
    }

    private ModelProfileRegistry fallbackProfileRegistry() {
        return new ModelProfileRegistry(new ModelProfilesProperties(Map.of(
                "scout",
                new ModelProfilesProperties.ProfileDefinition(
                        "openai-compatible",
                        "primary-model",
                        0.0d,
                        Duration.ofSeconds(1),
                        512,
                        new ModelProfilesProperties.RetryDefinition(0, Duration.ZERO),
                        "scout-fallback"),
                "scout-fallback",
                new ModelProfilesProperties.ProfileDefinition(
                        "openai-compatible",
                        "fallback-model",
                        0.0d,
                        Duration.ofSeconds(1),
                        512,
                        new ModelProfilesProperties.RetryDefinition(0, Duration.ZERO),
                        null))));
    }

    private ModelGateway.ModelRequest scoutRequest() {
        return new ModelGateway.ModelRequest(
                responseRequestReviewId(),
                RoleType.DIRECTOR,
                "scout",
                "scout-v1",
                "Inspect the repository.",
                "Public context.",
                Set.of("list_files"),
                "trace-fallback");
    }

    private ModelGateway.ModelRequest request() {
        return new ModelGateway.ModelRequest(
                responseRequestReviewId(),
                RoleType.BACKEND,
                "role-reviewer",
                "backend-v1",
                "Return JSON only.",
                "Public review context.",
                Set.of("searchText"),
                "trace-1");
    }

    private ReviewId responseRequestReviewId() {
        return new ReviewId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }
}
