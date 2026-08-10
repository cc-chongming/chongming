package ai.cc.chongming.review.domain.gateway;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Logical, vendor-neutral model profile referenced by RolePacks instead of hard-coded model IDs.
 *
 * [AIREVIEW-PLAN-023#8]
 *
 * @author zyj
 */
public record ModelProfile(
        String profileId,
        Provider provider,
        String modelName,
        double temperature,
        Duration timeout,
        int maxTokens,
        RetryPolicy retryPolicy,
        String fallbackProfileId,
        boolean streamEnabled) {

    public ModelProfile(
            String profileId,
            Provider provider,
            String modelName,
            double temperature,
            Duration timeout,
            int maxTokens,
            RetryPolicy retryPolicy,
            String fallbackProfileId) {
        this(profileId, provider, modelName, temperature, timeout, maxTokens, retryPolicy, fallbackProfileId, true);
    }

    public ModelProfile {
        requireText(profileId, "profileId");
        Objects.requireNonNull(provider, "provider must not be null");
        requireText(modelName, "modelName");
        if (temperature < 0.0d || temperature > 2.0d) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        fallbackProfileId = normalizeOptionalText(fallbackProfileId);
    }

    /**
     * Supported wire formats. DashScope-compatible endpoints share the OpenAI chat-completions shape.
     *
     * @author zyj
     */
    public enum Provider {
        OPENAI_COMPATIBLE,
        DASHSCOPE_COMPATIBLE;

        public static Provider fromConfiguration(String value) {
            requireText(value, "provider");
            return Provider.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Bounded retry policy for transient provider failures only.
     *
     * @author zyj
     */
    public record RetryPolicy(int maxRetries, Duration initialBackoff) {

        public RetryPolicy {
            if (maxRetries < 0 || maxRetries > 2) {
                throw new IllegalArgumentException("maxRetries must be between 0 and 2");
            }
            if (initialBackoff == null || initialBackoff.isNegative()) {
                throw new IllegalArgumentException("initialBackoff must not be negative");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
