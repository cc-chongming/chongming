package ai.cc.chongming.review.infrastructure.model;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.config.ModelGatewayProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException.Code;
import ai.cc.chongming.review.domain.gateway.ModelProfile;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Applies profile routing, bounded transient retries, cancellation and credential-safe auditing.
 *
 * @author wangli
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
                || exception.code() == Code.MODEL_PROVIDER_ERROR
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
}
