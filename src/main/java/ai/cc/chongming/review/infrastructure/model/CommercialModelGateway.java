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
import java.util.function.Function;
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

    private final ModelGatewayProperties properties;
    private final ModelProfileRegistry profileRegistry;
    private final ModelProviderClient providerClient;
    private final ModelCallAuditService auditService;
    private final Function<String, String> environment;

    @Autowired
    public CommercialModelGateway(
            ModelGatewayProperties properties,
            ModelProfileRegistry profileRegistry,
            ModelProviderClient providerClient,
            ModelCallAuditService auditService) {
        this(properties, profileRegistry, providerClient, auditService, System::getenv);
    }

    /**
     * Constructor for deterministic tests with an injected credential resolver.
     */
    public CommercialModelGateway(
            ModelGatewayProperties properties,
            ModelProfileRegistry profileRegistry,
            ModelProviderClient providerClient,
            ModelCallAuditService auditService,
            Function<String, String> environment) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry must not be null");
        this.providerClient = Objects.requireNonNull(providerClient, "providerClient must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    @Override
    public Mono<ModelResponse> generate(ModelRequest request, IntakeCancellation cancellation) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        return Mono.fromCallable(() -> call(request, cancellation)).subscribeOn(Schedulers.boundedElastic());
    }

    private ModelResponse call(ModelRequest request, IntakeCancellation cancellation) {
        checkCancelled(cancellation);
        ModelProfile profile = null;
        int attempts = 0;
        try {
            if (!properties.enabled()) {
                throw new ModelGatewayException(Code.MODEL_GATEWAY_DISABLED, "Model gateway is disabled");
            }
            profile = profileRegistry.requireProfile(request.profileId());
            String apiKey = environment.apply(properties.apiKeyEnvironmentVariable());
            if (apiKey == null || apiKey.isBlank() || properties.baseUrl() == null) {
                throw new ModelGatewayException(Code.MODEL_GATEWAY_DISABLED, "Model gateway is not configured");
            }
            Instant startedAt = Instant.now();
            int maxAttempts = profile.retryPolicy().maxRetries() + 1;
            ModelGatewayException lastFailure = null;
            while (attempts < maxAttempts) {
                attempts++;
                checkCancelled(cancellation);
                try {
                    ModelProviderClient.ProviderResponse response = providerClient.invoke(
                            new ModelProviderClient.ProviderRequest(properties.baseUrl(), apiKey, profile, request));
                    checkCancelled(cancellation);
                    ModelResponse normalized = new ModelResponse(
                            response.responseId(),
                            profile.modelName(),
                            response.publicText(),
                            response.usage(),
                            response.finishReason(),
                            Duration.between(startedAt, Instant.now()),
                            attempts,
                            request.traceId());
                    auditService.recordSuccess(request, profile, normalized);
                    return normalized;
                } catch (ModelGatewayException exception) {
                    lastFailure = exception;
                    if (!isRetryable(exception) || attempts >= maxAttempts) {
                        throw exception;
                    }
                    waitForRetry(profile.retryPolicy().initialBackoff(), attempts, cancellation);
                }
            }
            throw lastFailure == null
                    ? new ModelGatewayException(Code.MODEL_PROVIDER_ERROR, "Model provider did not produce a response")
                    : lastFailure;
        } catch (ModelGatewayException exception) {
            auditService.recordFailure(request, profile, exception.code(), Math.max(attempts, 1));
            throw exception;
        }
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
}
