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
                        new ModelProfilesProperties.RetryDefinition(maxRetries, Duration.ZERO)))));
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
