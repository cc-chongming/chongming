package ai.cc.chongming.review.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Non-sensitive logical model profile definitions bound beneath {@code review.model-gateway.profiles}.
 *
 * @author wangli
 */
@ConfigurationProperties(prefix = "review.model-gateway")
public record ModelProfilesProperties(@Valid Map<String, ProfileDefinition> profiles) {

    public ModelProfilesProperties {
        profiles = profiles == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(profiles));
    }

    /**
     * One logical model profile that can be referenced by a RolePack.
     *
     * @author wangli
     */
    public record ProfileDefinition(
            @NotBlank String provider,
            @NotBlank String modelName,
            double temperature,
            Duration timeout,
            int maxTokens,
            @Valid RetryDefinition retry,
            String fallbackProfile) {
    }

    /**
     * Transient-failure retry settings. The domain layer enforces the maximum of two retries.
     *
     * @author wangli
     */
    public record RetryDefinition(int maxRetries, Duration initialBackoff) {
    }
}
