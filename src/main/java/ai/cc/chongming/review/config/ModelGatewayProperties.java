package ai.cc.chongming.review.config;

import java.net.URI;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
        boolean logConversation) {

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
}
