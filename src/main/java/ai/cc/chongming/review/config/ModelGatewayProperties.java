package ai.cc.chongming.review.config;

import java.net.URI;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Non-sensitive gateway endpoint and credential-location configuration. Logical model names live in ModelProfilesProperties.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.model-gateway")
public record ModelGatewayProperties(
        boolean enabled,
        URI baseUrl,
        String modelName,
        @NotBlank String apiKeyEnvironmentVariable) {

    /**
     * Validates required model settings only when model calls are enabled.
     *
     * @return whether the configured gateway can be enabled safely
     */
    @AssertTrue(message = "enabled model gateway requires baseUrl and a configured environment key")
    public boolean isEnabledConfigurationValid() {
        return !enabled
                || (baseUrl != null
                && StringUtils.hasText(System.getenv(apiKeyEnvironmentVariable)));
    }
}
