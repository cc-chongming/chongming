package ai.cc.chongming.review.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.config.NotificationOutboxProperties;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.notification.LearningPlatformMcpAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * [AIREVIEW-PLAN-011#1.6] Ensures the unverified external MCP path fails closed rather than guessing a request.
 *
 * @author wangli
 */
class LearningPlatformMcpAdapterTests {

    @Test
    void rejectsDeliveryWhileExternalMcpIsDisabled() {
        LearningPlatformMcpAdapter adapter = new LearningPlatformMcpAdapter(
                new NotificationOutboxProperties(false, false, "learning-platform", "recipient-placeholder",
                        "MISSING_TEST_TOKEN", 3, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                mock(ObjectProvider.class));

        NotificationDeliveryException exception = assertThrows(NotificationDeliveryException.class,
                () -> adapter.deliver(new NotificationCommand(
                        new ReviewId(UUID.randomUUID()), 1L, "learning-platform", "recipient-placeholder",
                        GateResult.PASS, "approved", List.of(), "/api/reviews/example/report")));

        assertEquals("MCP_DISABLED", exception.code());
        assertEquals(false, exception.retryable());
    }
}
