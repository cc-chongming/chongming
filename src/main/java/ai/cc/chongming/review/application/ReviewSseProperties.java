package ai.cc.chongming.review.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * [AIREVIEW-PLAN-010#1.4] SSE connection and replay limits.
 *
 * @author wangli
 */
@ConfigurationProperties("review.sse")
public record ReviewSseProperties(Duration timeout, Duration heartbeatInterval, int replayBatchSize) {

    public ReviewSseProperties {
        timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(15) : heartbeatInterval;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        if (replayBatchSize <= 0 || replayBatchSize > 10_000) {
            throw new IllegalArgumentException("replayBatchSize must be between 1 and 10000");
        }
    }
}
