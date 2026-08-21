package ai.cc.chongming.review.config;

import java.net.URI;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Gateway configuration. Logical model names live in ModelProfilesProperties.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.model-gateway")
public record ModelGatewayProperties(
        boolean enabled,
        URI baseUrl,
        String modelName,
        String apiKey,
        boolean logConversation,
        CircuitBreaker circuitBreaker,
        Integer maxConcurrentCalls) {

    public static final int DEFAULT_MAX_CONCURRENT_CALLS = 4;

    @ConstructorBinding
    public ModelGatewayProperties {
        circuitBreaker = circuitBreaker == null ? new CircuitBreaker(null, null) : circuitBreaker;
        maxConcurrentCalls = maxConcurrentCalls == null ? DEFAULT_MAX_CONCURRENT_CALLS : maxConcurrentCalls;
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("maxConcurrentCalls must be positive");
        }
    }

    /**
     * Backward-compatible constructor for call sites that predate the circuit breaker settings.
     */
    public ModelGatewayProperties(boolean enabled, URI baseUrl, String modelName, String apiKey, boolean logConversation) {
        this(enabled, baseUrl, modelName, apiKey, logConversation, new CircuitBreaker(null, null), null);
    }

    /**
     * Backward-compatible constructor for call sites that predate the concurrency bound.
     */
    public ModelGatewayProperties(
            boolean enabled,
            URI baseUrl,
            String modelName,
            String apiKey,
            boolean logConversation,
            CircuitBreaker circuitBreaker) {
        this(enabled, baseUrl, modelName, apiKey, logConversation, circuitBreaker, null);
    }

    /**
     * Validates required model settings only when model calls are enabled.
     *
     * @return whether the configured gateway can be enabled safely
     */
    @AssertTrue(message = "enabled model gateway requires baseUrl and apiKey")
    public boolean isEnabledConfigurationValid() {
        return !enabled
                || (baseUrl != null
                && StringUtils.hasText(apiKey));
    }

    /**
     * [AIREVIEW-PLAN-024#6] Attempt-scoped breaker policy. After {@code failureThreshold}
     * consecutive transient failures the same profile routes directly to its fallback for the rest
     * of the review attempt; every {@code probeInterval} routed calls one half-open probe is sent
     * to the primary. A threshold of zero disables the breaker.
     *
     * @author wangli
     */
    public record CircuitBreaker(Integer failureThreshold, Integer probeInterval) {

        public static final int DEFAULT_FAILURE_THRESHOLD = 2;
        public static final int DEFAULT_PROBE_INTERVAL = 3;

        public CircuitBreaker {
            failureThreshold = failureThreshold == null ? DEFAULT_FAILURE_THRESHOLD : failureThreshold;
            probeInterval = probeInterval == null ? DEFAULT_PROBE_INTERVAL : probeInterval;
            if (failureThreshold < 0) {
                throw new IllegalArgumentException("circuit breaker failureThreshold must not be negative");
            }
            if (probeInterval < 1) {
                throw new IllegalArgumentException("circuit breaker probeInterval must be positive");
            }
        }
    }
}
