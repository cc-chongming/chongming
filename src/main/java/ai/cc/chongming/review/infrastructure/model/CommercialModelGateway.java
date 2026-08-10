package ai.cc.chongming.review.infrastructure.model;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.config.ModelGatewayProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException.Code;
import ai.cc.chongming.review.domain.gateway.ModelProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Applies profile routing, bounded transient retries, cancellation and credential-safe auditing.
 * <p>
 * [AIREVIEW-PLAN-023#8]
 *
 * @author zyj
 */
@Service
public class CommercialModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(CommercialModelGateway.class);

    private final ModelGatewayProperties properties;
    private final ModelProfileRegistry profileRegistry;
    private final ModelProviderClient providerClient;
    private final ModelCallAuditService auditService;

    @Autowired
    public CommercialModelGateway(
            ModelGatewayProperties properties,
            ModelProfileRegistry profileRegistry,
            ModelProviderClient providerClient,
            ModelCallAuditService auditService) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry must not be null");
        this.providerClient = Objects.requireNonNull(providerClient, "providerClient must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    @Override
    public Mono<ModelResponse> generate(ModelRequest request, IntakeCancellation cancellation) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        return Mono.fromCallable(() -> call(request, cancellation)).subscribeOn(Schedulers.boundedElastic());
    }

    private ModelResponse call(ModelRequest request, IntakeCancellation cancellation) {
        checkCancelled(cancellation);
        ModelProfile effectiveProfile = null;
        AttemptCounter attempts = new AttemptCounter();
        try {
            if (!properties.enabled()) {
                throw new ModelGatewayException(Code.MODEL_GATEWAY_DISABLED, "Model gateway is disabled");
            }
            ModelProfile primaryProfile = profileRegistry.requireProfile(request.profileId());
            effectiveProfile = primaryProfile;
            String apiKey = properties.apiKey();
            if (apiKey == null || apiKey.isBlank() || properties.baseUrl() == null) {
                throw new ModelGatewayException(Code.MODEL_GATEWAY_DISABLED, "Model gateway is not configured");
            }
            Instant startedAt = Instant.now();
            ModelProviderClient.ProviderResponse providerResponse;
            try {
                providerResponse = invokeWithRetries(primaryProfile, request, apiKey, cancellation, attempts);
            } catch (ModelGatewayException primaryFailure) {
                if (!shouldFallback(primaryProfile, primaryFailure)) {
                    throw primaryFailure;
                }
                auditService.recordFailure(request, primaryProfile, primaryFailure.code(), attempts.value());
                ModelProfile fallbackProfile = profileRegistry.requireProfile(primaryProfile.fallbackProfileId());
                effectiveProfile = fallbackProfile;
                log.warn(
                        "model_profile_fallback traceId={} primaryProfile={} fallbackProfile={} failureCode={} primaryAttempts={}",
                        request.traceId(),
                        primaryProfile.profileId(),
                        fallbackProfile.profileId(),
                        primaryFailure.code(),
                        attempts.value());
                providerResponse = invokeWithRetries(fallbackProfile, request, apiKey, cancellation, attempts);
            }
            ModelResponse normalized = new ModelResponse(
                    providerResponse.responseId(),
                    effectiveProfile.modelName(),
                    providerResponse.publicText(),
                    providerResponse.thinkingText(),
                    providerResponse.usage(),
                    providerResponse.finishReason(),
                    Duration.between(startedAt, Instant.now()),
                    attempts.value(),
                    providerResponse.toolCalls(),
                    request.traceId());
            auditService.recordSuccess(request, effectiveProfile, normalized);
            return normalized;
        } catch (ModelGatewayException exception) {
            auditService.recordFailure(request, effectiveProfile, exception.code(), Math.max(attempts.value(), 1));
            throw exception;
        }
    }

    @Override
    public Flux<ModelStreamChunk> stream(ModelRequest request, IntakeCancellation cancellation) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        return Flux.defer(() -> streamCall(request, cancellation));
    }

    private Flux<ModelStreamChunk> streamCall(ModelRequest request, IntakeCancellation cancellation) {
        checkCancelled(cancellation);
        if (!properties.enabled()) {
            return Flux.error(new ModelGatewayException(Code.MODEL_GATEWAY_DISABLED, "Model gateway is disabled"));
        }
        ModelProfile primaryProfile = profileRegistry.requireProfile(request.profileId());
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank() || properties.baseUrl() == null) {
            return Flux.error(new ModelGatewayException(Code.MODEL_GATEWAY_DISABLED, "Model gateway is not configured"));
        }

        AttemptCounter attempts = new AttemptCounter();
        StreamingCallState state = new StreamingCallState(primaryProfile, Instant.now());
        AtomicBoolean primaryEmitted = new AtomicBoolean();
        Flux<ModelProviderClient.ProviderStreamChunk> providerStream = invokeStreamWithRetries(
                primaryProfile, request, apiKey, cancellation, attempts, 1)
                .doOnNext(ignored -> primaryEmitted.set(true))
                .onErrorResume(ModelGatewayException.class, primaryFailure -> {
                    if (primaryEmitted.get() || !shouldFallback(primaryProfile, primaryFailure)) {
                        return Flux.error(primaryFailure);
                    }
                    auditService.recordFailure(request, primaryProfile, primaryFailure.code(), attempts.value());
                    ModelProfile fallbackProfile = profileRegistry.requireProfile(primaryProfile.fallbackProfileId());
                    state.useProfile(fallbackProfile);
                    log.warn(
                            "model_profile_stream_fallback traceId={} primaryProfile={} fallbackProfile={} failureCode={} primaryAttempts={}",
                            request.traceId(),
                            primaryProfile.profileId(),
                            fallbackProfile.profileId(),
                            primaryFailure.code(),
                            attempts.value());
                    return invokeStreamWithRetries(fallbackProfile, request, apiKey, cancellation, attempts, 1);
                });

        return providerStream
                .doOnNext(chunk -> {
                    checkCancelled(cancellation);
                    state.accept(chunk);
                })
                .map(chunk -> state.toModelChunk(chunk, attempts.value(), request.traceId()))
                .doOnNext(chunk -> {
                    if (chunk.terminal()) {
                        auditService.recordSuccess(request, state.profile(), state.toModelResponse(request.traceId(), attempts.value()));
                    }
                })
                .concatWith(Flux.defer(state::verifyTerminal))
                .onErrorMap(failure -> failure instanceof ModelGatewayException
                        ? failure
                        : new ModelGatewayException(Code.MODEL_PROVIDER_ERROR, "Model provider stream failed", failure))
                .doOnError(ModelGatewayException.class, failure -> auditService.recordFailure(
                        request, state.profile(), failure.code(), Math.max(attempts.value(), 1)));
    }

    private Flux<ModelProviderClient.ProviderStreamChunk> invokeStreamWithRetries(
            ModelProfile profile,
            ModelRequest request,
            String apiKey,
            IntakeCancellation cancellation,
            AttemptCounter attempts,
            int profileAttempt) {
        return Flux.defer(() -> {
            attempts.increment();
            checkCancelled(cancellation);
            AtomicBoolean emitted = new AtomicBoolean();
            ModelProviderClient.ProviderRequest providerRequest = new ModelProviderClient.ProviderRequest(
                    properties.baseUrl(), apiKey, profile, request, properties.logConversation());
            Flux<ModelProviderClient.ProviderStreamChunk> invocation = profile.streamEnabled()
                    ? providerClient.stream(providerRequest)
                    : Flux.defer(() -> Flux.just(ModelProviderClient.ProviderStreamChunk.from(
                            providerClient.invoke(providerRequest))));
            return invocation
                    .doOnNext(ignored -> emitted.set(true))
                    .onErrorResume(ModelGatewayException.class, failure -> {
                        int maxAttempts = profile.retryPolicy().maxRetries() + 1;
                        if (emitted.get() || !isRetryable(failure) || profileAttempt >= maxAttempts) {
                            return Flux.error(failure);
                        }
                        waitForRetry(profile.retryPolicy().initialBackoff(), profileAttempt, cancellation);
                        return invokeStreamWithRetries(
                                profile,
                                request,
                                apiKey,
                                cancellation,
                                attempts,
                                profileAttempt + 1);
                    });
        });
    }

    private ModelProviderClient.ProviderResponse invokeWithRetries(
            ModelProfile profile,
            ModelRequest request,
            String apiKey,
            IntakeCancellation cancellation,
            AttemptCounter attempts) {
        int maxAttempts = profile.retryPolicy().maxRetries() + 1;
        for (int profileAttempt = 1; profileAttempt <= maxAttempts; profileAttempt++) {
            attempts.increment();
            checkCancelled(cancellation);
            try {
                ModelProviderClient.ProviderResponse response = providerClient.invoke(
                        new ModelProviderClient.ProviderRequest(
                                properties.baseUrl(), apiKey, profile, request, properties.logConversation()));
                checkCancelled(cancellation);
                return response;
            } catch (ModelGatewayException exception) {
                if (!isRetryable(exception) || profileAttempt >= maxAttempts) {
                    throw exception;
                }
                waitForRetry(profile.retryPolicy().initialBackoff(), profileAttempt, cancellation);
            }
        }
        throw new ModelGatewayException(Code.MODEL_PROVIDER_ERROR, "Model provider did not produce a response");
    }

    private boolean shouldFallback(ModelProfile primaryProfile, ModelGatewayException failure) {
        return primaryProfile.fallbackProfileId() != null
                && !primaryProfile.profileId().equals(primaryProfile.fallbackProfileId())
                && isRetryable(failure);
    }

    private boolean isRetryable(ModelGatewayException exception) {
        return exception.code() == Code.MODEL_CALL_TIMEOUT
                || exception.code() == Code.MODEL_RATE_LIMITED
                || exception.code() == Code.MODEL_NETWORK_ERROR
                || exception.code() == Code.MODEL_PROVIDER_ERROR;
    }

    private void waitForRetry(Duration initialBackoff, int completedAttempts, IntakeCancellation cancellation) {
        long delayMillis = initialBackoff.toMillis() * (1L << (completedAttempts - 1));
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(Code.MODEL_CANCELLED, "Model call was interrupted", exception);
        }
        checkCancelled(cancellation);
    }

    private void checkCancelled(IntakeCancellation cancellation) {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new ModelGatewayException(Code.MODEL_CANCELLED, "Model call was cancelled");
        }
    }

    private static final class AttemptCounter {

        private int value;

        private void increment() {
            value++;
        }

        private int value() {
            return value;
        }
    }

    private static final class StreamingCallState {

        private final Instant startedAt;
        private final StringBuilder publicText = new StringBuilder();
        private final List<ModelGateway.ToolCall> toolCalls = new ArrayList<>();
        private ModelProfile profile;
        private String responseId;
        private ModelGateway.Usage usage = new ModelGateway.Usage(0, 0, 0);
        private ModelGateway.FinishReason finishReason = ModelGateway.FinishReason.UNKNOWN;
        private boolean terminal;

        private StreamingCallState(ModelProfile profile, Instant startedAt) {
            this.profile = profile;
            this.startedAt = startedAt;
        }

        private void useProfile(ModelProfile profile) {
            this.profile = profile;
        }

        private ModelProfile profile() {
            return profile;
        }

        private void accept(ModelProviderClient.ProviderStreamChunk chunk) {
            if (terminal) {
                throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model provider emitted data after terminal chunk");
            }
            if (responseId != null && !responseId.equals(chunk.responseId())) {
                throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model provider response id changed");
            }
            responseId = chunk.responseId();
            publicText.append(chunk.publicTextDelta());
            if (chunk.terminal()) {
                usage = chunk.usage();
                finishReason = chunk.finishReason();
                toolCalls.addAll(chunk.toolCalls());
                terminal = true;
            }
        }

        private ModelStreamChunk toModelChunk(
                ModelProviderClient.ProviderStreamChunk chunk, int attempts, String traceId) {
            return new ModelStreamChunk(
                    chunk.responseId(),
                    chunk.publicTextDelta(),
                    chunk.usage(),
                    chunk.finishReason(),
                    Duration.between(startedAt, Instant.now()),
                    attempts,
                    chunk.toolCalls(),
                    traceId,
                    chunk.terminal());
        }

        private ModelResponse toModelResponse(String traceId, int attempts) {
            return new ModelResponse(
                    responseId,
                    profile.modelName(),
                    publicText.toString(),
                    "",
                    usage,
                    finishReason,
                    Duration.between(startedAt, Instant.now()),
                    attempts,
                    List.copyOf(toolCalls),
                    traceId);
        }

        private Flux<ModelStreamChunk> verifyTerminal() {
            if (terminal) {
                return Flux.empty();
            }
            return Flux.error(new ModelGatewayException(
                    Code.MODEL_RESPONSE_INVALID, "Model provider stream ended without a terminal chunk"));
        }
    }
}
