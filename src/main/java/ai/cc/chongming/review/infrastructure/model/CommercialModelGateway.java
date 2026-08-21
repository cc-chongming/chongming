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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
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
 * [AIREVIEW-PLAN-023#8][AIREVIEW-PLAN-024#6] Since PLAN-024 the gateway also applies an
 * attempt-scoped circuit breaker keyed by provider/model/profile: after a configured number of
 * consecutive transient failures, later same-profile calls inside the same review attempt route
 * straight to the configured fallback, with periodic half-open probes and recovery logging. The
 * breaker never persists across reviews or attempts and does not replace the single-failure
 * fallback path.
 *
 * @author zyj
 */
@Service
public class CommercialModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(CommercialModelGateway.class);

    /** Bound on retained per-attempt breaker states; the eldest entry is evicted beyond it. */
    private static final int MAX_BREAKER_STATES = 1024;

    private final ModelGatewayProperties properties;
    private final ModelProfileRegistry profileRegistry;
    private final ModelProviderClient providerClient;
    private final ModelCallAuditService auditService;
    // [AIREVIEW-PLAN-030] Some provider tokens reject simultaneous requests (new-api returned
    // "Invalid token" for any concurrency above one). The permit bound keeps parallel role
    // rounds from tripping that limit; configure 1 for such providers.
    private final Semaphore concurrency;
    private final Map<String, BreakerState> circuitBreakers =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BreakerState> eldest) {
                    return size() > MAX_BREAKER_STATES;
                }
            });

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
        this.concurrency = new Semaphore(properties.maxConcurrentCalls(), true);
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
            BreakerState breaker = breakerStateFor(request, primaryProfile);
            BreakerDecision breakerDecision = breaker == null ? BreakerDecision.CLOSED : breaker.beforeCall();
            ModelProviderClient.ProviderResponse providerResponse;
            if (breakerDecision == BreakerDecision.OPEN) {
                // [AIREVIEW-PLAN-024#6] The breaker is tripped for this attempt: skip the primary
                // entirely and route straight to the configured fallback, recording the reason.
                ModelProfile fallbackProfile = profileRegistry.fallbackProfile(primaryProfile).orElseThrow();
                effectiveProfile = fallbackProfile;
                log.warn(
                        "model_circuit_breaker_route traceId={} primaryProfile={} fallbackProfile={} failureCode={} consecutiveFailures={} threshold={}",
                        request.traceId(),
                        primaryProfile.profileId(),
                        fallbackProfile.profileId(),
                        breaker.lastFailureCode(),
                        breaker.consecutiveFailures(),
                        properties.circuitBreaker().failureThreshold());
                auditService.recordFailure(request, primaryProfile, breaker.lastFailureCode(), 0);
                providerResponse = invokeWithRetries(fallbackProfile, request, apiKey, cancellation, attempts);
            } else {
                if (breakerDecision == BreakerDecision.PROBE) {
                    log.info(
                            "model_circuit_breaker_probe traceId={} primaryProfile={} provider={} model={}",
                            request.traceId(),
                            primaryProfile.profileId(),
                            primaryProfile.provider(),
                            primaryProfile.modelName());
                }
                try {
                    providerResponse = invokeWithRetries(primaryProfile, request, apiKey, cancellation, attempts);
                    if (breaker != null) {
                        breaker.recordSuccess(request.traceId(), primaryProfile);
                    }
                } catch (ModelGatewayException primaryFailure) {
                    recordBreakerFailure(breaker, breakerDecision, request, primaryProfile, primaryFailure);
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
        BreakerState breaker = breakerStateFor(request, primaryProfile);
        BreakerDecision breakerDecision = breaker == null ? BreakerDecision.CLOSED : breaker.beforeCall();
        Flux<ModelProviderClient.ProviderStreamChunk> providerStream;
        if (breakerDecision == BreakerDecision.OPEN) {
            // [AIREVIEW-PLAN-024#6] Tripped breaker routes the stream straight to the fallback.
            ModelProfile fallbackProfile = profileRegistry.fallbackProfile(primaryProfile).orElseThrow();
            state.useProfile(fallbackProfile);
            log.warn(
                    "model_circuit_breaker_stream_route traceId={} primaryProfile={} fallbackProfile={} failureCode={} consecutiveFailures={} threshold={}",
                    request.traceId(),
                    primaryProfile.profileId(),
                    fallbackProfile.profileId(),
                    breaker.lastFailureCode(),
                    breaker.consecutiveFailures(),
                    properties.circuitBreaker().failureThreshold());
            auditService.recordFailure(request, primaryProfile, breaker.lastFailureCode(), 0);
            providerStream = invokeStreamWithRetries(fallbackProfile, request, apiKey, cancellation, attempts, 1);
        } else {
            if (breakerDecision == BreakerDecision.PROBE) {
                log.info(
                        "model_circuit_breaker_probe traceId={} primaryProfile={} provider={} model={}",
                        request.traceId(),
                        primaryProfile.profileId(),
                        primaryProfile.provider(),
                        primaryProfile.modelName());
            }
            AtomicBoolean primaryEmitted = new AtomicBoolean();
            providerStream = invokeStreamWithRetries(
                    primaryProfile, request, apiKey, cancellation, attempts, 1)
                    .doOnNext(ignored -> {
                        if (primaryEmitted.compareAndSet(false, true) && breaker != null) {
                            breaker.recordSuccess(request.traceId(), primaryProfile);
                        }
                    })
                    .onErrorResume(ModelGatewayException.class, primaryFailure -> {
                        if (primaryEmitted.get() || !shouldFallback(primaryProfile, primaryFailure)) {
                            return Flux.error(primaryFailure);
                        }
                        recordBreakerFailure(breaker, breakerDecision, request, primaryProfile, primaryFailure);
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
        }

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
            Flux<ModelProviderClient.ProviderStreamChunk> invocation = Flux.using(
                    this::acquireConcurrencyPermit,
                    permit -> profile.streamEnabled()
                            ? providerClient.stream(providerRequest)
                            : Flux.defer(() -> Flux.just(ModelProviderClient.ProviderStreamChunk.from(
                                    providerClient.invoke(providerRequest)))),
                    Semaphore::release);
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
                acquireConcurrency();
                ModelProviderClient.ProviderResponse response;
                try {
                    response = providerClient.invoke(
                            new ModelProviderClient.ProviderRequest(
                                    properties.baseUrl(), apiKey, profile, request, properties.logConversation()));
                } finally {
                    concurrency.release();
                }
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

    private void acquireConcurrency() {
        try {
            concurrency.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(
                    Code.MODEL_NETWORK_ERROR, "Interrupted while waiting for a model concurrency permit", exception);
        }
    }

    /**
     * Flux.using ties the permit to the stream resource so complete, error and cancel all
     * release it; a doOnSubscribe/doFinally pair leaked permits when the orchestration
     * subscribed the next role synchronously on the completing thread.
     */
    private Semaphore acquireConcurrencyPermit() {
        acquireConcurrency();
        return concurrency;
    }

    private boolean shouldFallback(ModelProfile primaryProfile, ModelGatewayException failure) {
        return primaryProfile.fallbackProfileId() != null
                && !primaryProfile.profileId().equals(primaryProfile.fallbackProfileId())
                && isRetryable(failure);
    }

    /**
     * [AIREVIEW-PLAN-024#6] Resolves the attempt-scoped breaker for a provider/model/profile key.
     * The review attempt trace id keeps the breaker from ever spanning reviews or attempts. Returns
     * {@code null} when the breaker is disabled or the profile has no configured fallback to route
     * to, in which case the legacy single-failure fallback remains the only protection.
     */
    private BreakerState breakerStateFor(ModelRequest request, ModelProfile profile) {
        ModelGatewayProperties.CircuitBreaker config = properties.circuitBreaker();
        if (config.failureThreshold() <= 0 || profile.fallbackProfileId() == null) {
            return null;
        }
        String key = request.traceId() + '|' + profile.provider() + '|' + profile.modelName() + '|' + profile.profileId();
        return circuitBreakers.computeIfAbsent(
                key, ignored -> new BreakerState(config.failureThreshold(), config.probeInterval()));
    }

    /**
     * [AIREVIEW-PLAN-024#6] Counts a primary failure against the breaker and logs transitions:
     * a newly opened breaker, or a failed half-open probe that re-arms the routing counter. Only
     * transient (retryable) failures trip the breaker; deterministic rejections such as invalid
     * requests are not provider-health signals.
     */
    private void recordBreakerFailure(
            BreakerState breaker,
            BreakerDecision decision,
            ModelRequest request,
            ModelProfile primaryProfile,
            ModelGatewayException failure) {
        if (breaker == null || !isRetryable(failure)) {
            return;
        }
        breaker.recordFailure(failure.code());
        if (!breaker.isOpen()) {
            return;
        }
        if (decision == BreakerDecision.PROBE) {
            log.warn(
                    "model_circuit_breaker_probe_failed traceId={} primaryProfile={} failureCode={} consecutiveFailures={}",
                    request.traceId(),
                    primaryProfile.profileId(),
                    failure.code(),
                    breaker.consecutiveFailures());
        } else {
            log.warn(
                    "model_circuit_breaker_open traceId={} primaryProfile={} provider={} model={} failureCode={} consecutiveFailures={} threshold={} fallback={}",
                    request.traceId(),
                    primaryProfile.profileId(),
                    primaryProfile.provider(),
                    primaryProfile.modelName(),
                    failure.code(),
                    breaker.consecutiveFailures(),
                    properties.circuitBreaker().failureThreshold(),
                    primaryProfile.fallbackProfileId());
        }
    }

    private boolean isRetryable(ModelGatewayException exception) {
        return exception.code() == Code.MODEL_CALL_TIMEOUT
                || exception.code() == Code.MODEL_RATE_LIMITED
                || exception.code() == Code.MODEL_NETWORK_ERROR
                || exception.code() == Code.MODEL_PROVIDER_ERROR
                // [AIREVIEW-PLAN-030] Empty or malformed provider streams (e.g. reasoning-only
                // responses with no public text) are transient provider glitches; bounded
                // retries plus fallback-profile escalation keep one bad stream from failing
                // the whole review at INITIAL_REVIEW.
                || exception.code() == Code.MODEL_RESPONSE_INVALID;
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

    /**
     * [AIREVIEW-PLAN-024#6] Breaker routing outcome for one call.
     *
     * @author wangli
     */
    private enum BreakerDecision {
        CLOSED,
        OPEN,
        PROBE
    }

    /**
     * [AIREVIEW-PLAN-024#6] Mutable attempt-scoped breaker state for one provider/model/profile
     * key. CLOSED counts consecutive transient call failures; once the threshold is reached the
     * breaker OPENs and later calls route to the fallback without contacting the primary. Every
     * {@code probeInterval} routed calls one half-open PROBE is allowed; a probe success recovers
     * (closes) the breaker, a probe failure re-arms the routing counter.
     *
     * @author wangli
     */
    private static final class BreakerState {

        private final int failureThreshold;
        private final int probeInterval;
        private boolean open;
        private int consecutiveFailures;
        private int routedSinceOpen;
        private Code lastFailureCode;

        private BreakerState(int failureThreshold, int probeInterval) {
            this.failureThreshold = failureThreshold;
            this.probeInterval = probeInterval;
        }

        private synchronized BreakerDecision beforeCall() {
            if (!open) {
                return BreakerDecision.CLOSED;
            }
            routedSinceOpen++;
            return routedSinceOpen >= probeInterval ? BreakerDecision.PROBE : BreakerDecision.OPEN;
        }

        private synchronized void recordSuccess(String traceId, ModelProfile profile) {
            if (open) {
                log.info(
                        "model_circuit_breaker_recovered traceId={} primaryProfile={} provider={} model={}",
                        traceId,
                        profile.profileId(),
                        profile.provider(),
                        profile.modelName());
            }
            open = false;
            consecutiveFailures = 0;
            routedSinceOpen = 0;
        }

        private synchronized void recordFailure(Code failureCode) {
            lastFailureCode = failureCode;
            consecutiveFailures++;
            routedSinceOpen = 0;
            if (!open && consecutiveFailures >= failureThreshold) {
                open = true;
            }
        }

        private synchronized boolean isOpen() {
            return open;
        }

        private synchronized int consecutiveFailures() {
            return consecutiveFailures;
        }

        private synchronized Code lastFailureCode() {
            return lastFailureCode == null ? Code.MODEL_PROVIDER_ERROR : lastFailureCode;
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
