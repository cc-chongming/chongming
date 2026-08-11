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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Tests deterministic commercial-gateway behavior without contacting a real model provider.
 * <p>
 * [AIREVIEW-PLAN-023#8][AIREVIEW-PLAN-024#6]
 *
 * @author zyj
 */
class ModelGatewayContractTests {

    @Test
    void streamsProviderDeltasAndAuditsTheAggregatedPublicResult() {
        ModelProviderClient provider = new ModelProviderClient() {
            @Override
            public ProviderResponse invoke(ProviderRequest request) {
                throw new AssertionError("non-streaming fallback must not be used");
            }

            @Override
            public Flux<ProviderStreamChunk> stream(ProviderRequest request) {
                return Flux.just(
                        new ProviderStreamChunk(
                                "stream-1", "公开", new ModelGateway.Usage(0, 0, 0),
                                ModelGateway.FinishReason.UNKNOWN, List.of(), false),
                        new ProviderStreamChunk(
                                "stream-1", "结论", new ModelGateway.Usage(0, 0, 0),
                                ModelGateway.FinishReason.UNKNOWN, List.of(), false),
                        new ProviderStreamChunk(
                                "stream-1", "", new ModelGateway.Usage(3, 5, 8),
                                ModelGateway.FinishReason.STOP, List.of(), true));
            }
        };
        ModelCallAuditService audit = new ModelCallAuditService();
        CommercialModelGateway gateway = new CommercialModelGateway(
                enabledProperties(), profileRegistry(0), provider, audit);

        List<ModelGateway.ModelStreamChunk> chunks =
                gateway.stream(request(), IntakeCancellation.neverCancelled()).collectList().block();

        assertThat(chunks).hasSize(3);
        assertThat(chunks.subList(0, 2)).extracting(ModelGateway.ModelStreamChunk::publicTextDelta)
                .containsExactly("公开", "结论");
        assertThat(chunks.getLast().terminal()).isTrue();
        assertThat(audit.findByReview(responseRequestReviewId())).singleElement().satisfies(entry -> {
            assertThat(entry.outputHash()).hasSize(64);
            assertThat(entry.usage()).isEqualTo(new ModelGateway.Usage(3, 5, 8));
        });
    }

    @Test
    void doesNotRetryAfterTheFirstPublicDelta() {
        AtomicInteger calls = new AtomicInteger();
        ModelProviderClient provider = new ModelProviderClient() {
            @Override
            public ProviderResponse invoke(ProviderRequest request) {
                throw new AssertionError("non-streaming fallback must not be used");
            }

            @Override
            public Flux<ProviderStreamChunk> stream(ProviderRequest request) {
                calls.incrementAndGet();
                return Flux.concat(
                        Flux.just(new ProviderStreamChunk(
                                "stream-1", "已输出", new ModelGateway.Usage(0, 0, 0),
                                ModelGateway.FinishReason.UNKNOWN, List.of(), false)),
                        Flux.error(new ModelGatewayException(Code.MODEL_RATE_LIMITED, "transient")));
            }
        };
        CommercialModelGateway gateway = new CommercialModelGateway(
                enabledProperties(), profileRegistry(1), provider, new ModelCallAuditService());

        assertThatThrownBy(() -> gateway.stream(request(), IntakeCancellation.neverCancelled()).collectList().block())
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(error -> assertThat(((ModelGatewayException) error).code())
                        .isEqualTo(Code.MODEL_RATE_LIMITED));
        assertThat(calls).hasValue(1);
    }

    @Test
    void usesOneTerminalNonStreamingChunkWhenProfileDisablesSse() {
        AtomicInteger synchronousCalls = new AtomicInteger();
        ModelProviderClient provider = new ModelProviderClient() {
            @Override
            public ProviderResponse invoke(ProviderRequest request) {
                synchronousCalls.incrementAndGet();
                return new ProviderResponse(
                        "fallback-1",
                        "完整公开结论",
                        new ModelGateway.Usage(2, 4, 6),
                        ModelGateway.FinishReason.STOP);
            }

            @Override
            public Flux<ProviderStreamChunk> stream(ProviderRequest request) {
                return Flux.error(new AssertionError("SSE must not be used for this profile"));
            }
        };
        CommercialModelGateway gateway = new CommercialModelGateway(
                enabledProperties(), nonStreamingProfileRegistry(), provider, new ModelCallAuditService());

        List<ModelGateway.ModelStreamChunk> chunks =
                gateway.stream(request(), IntakeCancellation.neverCancelled()).collectList().block();

        assertThat(synchronousCalls).hasValue(1);
        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.publicTextDelta()).isEqualTo("完整公开结论");
            assertThat(chunk.terminal()).isTrue();
        });
    }

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

    @Test
    void routesStraightToFallbackAfterConsecutiveFailuresTripAttemptBreaker() {
        List<String> invokedProfiles = new CopyOnWriteArrayList<>();
        ModelProviderClient provider = providerRequest -> {
            invokedProfiles.add(providerRequest.profile().profileId());
            if ("scout".equals(providerRequest.profile().profileId())) {
                throw new ModelGatewayException(Code.MODEL_CALL_TIMEOUT, "primary timed out");
            }
            return new ModelProviderClient.ProviderResponse(
                    "fallback-response",
                    "{\"summary\":\"fallback\"}",
                    new ModelGateway.Usage(1, 1, 2),
                    ModelGateway.FinishReason.STOP);
        };
        ModelCallAuditService audit = new ModelCallAuditService();
        CommercialModelGateway gateway = new CommercialModelGateway(
                breakerProperties(2, 100), fallbackProfileRegistry(), provider, audit);

        for (int call = 0; call < 3; call++) {
            ModelGateway.ModelResponse response =
                    gateway.generate(scoutRequest("trace-breaker"), IntakeCancellation.neverCancelled()).block();
            assertThat(response.modelName()).isEqualTo("fallback-model");
        }

        // Calls 1 and 2 try the primary and fail over; call 3 is breaker-routed and never
        // contacts the primary again within the same attempt.
        assertThat(invokedProfiles)
                .containsExactly("scout", "scout-fallback", "scout", "scout-fallback", "scout-fallback");
        assertThat(audit.findByReview(responseRequestReviewId()))
                .anySatisfy(entry -> {
                    assertThat(entry.profileId()).isEqualTo("scout");
                    assertThat(entry.failureCode()).isEqualTo(Code.MODEL_CALL_TIMEOUT.name());
                    assertThat(entry.attempts()).isZero();
                });
    }

    @Test
    void breakerDoesNotTripAcrossReviewAttempts() {
        List<String> invokedProfiles = new CopyOnWriteArrayList<>();
        ModelProviderClient provider = providerRequest -> {
            invokedProfiles.add(providerRequest.profile().profileId());
            if ("scout".equals(providerRequest.profile().profileId())) {
                throw new ModelGatewayException(Code.MODEL_CALL_TIMEOUT, "primary timed out");
            }
            return new ModelProviderClient.ProviderResponse(
                    "fallback-response",
                    "{\"summary\":\"fallback\"}",
                    new ModelGateway.Usage(1, 1, 2),
                    ModelGateway.FinishReason.STOP);
        };
        CommercialModelGateway gateway = new CommercialModelGateway(
                breakerProperties(1, 100), fallbackProfileRegistry(), provider, new ModelCallAuditService());

        gateway.generate(scoutRequest("trace-attempt-1"), IntakeCancellation.neverCancelled()).block();
        // A different attempt trace id starts from a fresh breaker: the primary is probed again.
        gateway.generate(scoutRequest("trace-attempt-2"), IntakeCancellation.neverCancelled()).block();

        assertThat(invokedProfiles)
                .containsExactly("scout", "scout-fallback", "scout", "scout-fallback");
    }

    @Test
    void halfOpenProbeRecoversBreakerWhenPrimaryRecovers() {
        List<String> invokedProfiles = new CopyOnWriteArrayList<>();
        AtomicInteger scoutCalls = new AtomicInteger();
        ModelProviderClient provider = providerRequest -> {
            invokedProfiles.add(providerRequest.profile().profileId());
            if ("scout".equals(providerRequest.profile().profileId()) && scoutCalls.incrementAndGet() == 1) {
                throw new ModelGatewayException(Code.MODEL_CALL_TIMEOUT, "primary timed out");
            }
            return new ModelProviderClient.ProviderResponse(
                    "primary-response",
                    "{\"summary\":\"recovered\"}",
                    new ModelGateway.Usage(1, 1, 2),
                    ModelGateway.FinishReason.STOP);
        };
        CommercialModelGateway gateway = new CommercialModelGateway(
                breakerProperties(1, 2), fallbackProfileRegistry(), provider, new ModelCallAuditService());

        // Call 1: primary fails and opens the breaker; fail over to the fallback.
        gateway.generate(scoutRequest("trace-probe"), IntakeCancellation.neverCancelled()).block();
        // Call 2: breaker open, routed straight to the fallback (1 of 2 routed calls).
        gateway.generate(scoutRequest("trace-probe"), IntakeCancellation.neverCancelled()).block();
        // Call 3: half-open probe hits the recovered primary and closes the breaker.
        ModelGateway.ModelResponse probeResponse =
                gateway.generate(scoutRequest("trace-probe"), IntakeCancellation.neverCancelled()).block();
        // Call 4: breaker closed again, the primary is used directly.
        ModelGateway.ModelResponse recoveredResponse =
                gateway.generate(scoutRequest("trace-probe"), IntakeCancellation.neverCancelled()).block();

        assertThat(invokedProfiles)
                .containsExactly("scout", "scout-fallback", "scout-fallback", "scout", "scout");
        assertThat(probeResponse.modelName()).isEqualTo("primary-model");
        assertThat(recoveredResponse.modelName()).isEqualTo("primary-model");
    }

    private ModelGatewayProperties enabledProperties() {
        return new ModelGatewayProperties(true, URI.create("https://example.invalid/v1"), "placeholder", "test-key", false);
    }

    private ModelGatewayProperties breakerProperties(int failureThreshold, int probeInterval) {
        return new ModelGatewayProperties(
                true,
                URI.create("https://example.invalid/v1"),
                "placeholder",
                "test-key",
                false,
                new ModelGatewayProperties.CircuitBreaker(failureThreshold, probeInterval));
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

    private ModelProfileRegistry nonStreamingProfileRegistry() {
        return new ModelProfileRegistry(new ModelProfilesProperties(Map.of(
                "role-reviewer",
                new ModelProfilesProperties.ProfileDefinition(
                        "openai-compatible",
                        "test-model",
                        0.2d,
                        Duration.ofSeconds(1),
                        256,
                        new ModelProfilesProperties.RetryDefinition(0, Duration.ZERO),
                        null,
                        false))));
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
        return scoutRequest("trace-fallback");
    }

    private ModelGateway.ModelRequest scoutRequest(String traceId) {
        return new ModelGateway.ModelRequest(
                responseRequestReviewId(),
                RoleType.DIRECTOR,
                "scout",
                "scout-v1",
                "Inspect the repository.",
                "Public context.",
                Set.of("list_files"),
                traceId);
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
