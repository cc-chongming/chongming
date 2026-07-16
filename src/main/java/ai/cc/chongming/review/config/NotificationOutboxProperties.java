package ai.cc.chongming.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * [AIREVIEW-PLAN-011#1.5,#1.6] Safe-by-default notification queue and MCP integration settings.
 *
 * @author wangli
 */
@ConfigurationProperties("review.notification")
public record NotificationOutboxProperties(
        boolean workerEnabled,
        boolean mcpEnabled,
        String channel,
        String destination,
        String credentialEnvironmentVariable,
        int maxAttempts,
        Duration initialRetryDelay,
        Duration workerDelay) {

    public NotificationOutboxProperties {
        channel = requireText(channel, "channel");
        destination = requireText(destination, "destination");
        credentialEnvironmentVariable = requireText(credentialEnvironmentVariable, "credentialEnvironmentVariable");
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 20");
        }
        initialRetryDelay = initialRetryDelay == null ? Duration.ofSeconds(30) : initialRetryDelay;
        workerDelay = workerDelay == null ? Duration.ofSeconds(5) : workerDelay;
        if (initialRetryDelay.isNegative() || initialRetryDelay.isZero()
                || workerDelay.isNegative() || workerDelay.isZero()) {
            throw new IllegalArgumentException("notification delays must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
