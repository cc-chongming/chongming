package ai.cc.chongming.review.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.application.NotificationDeliveryPort;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.notification.LearningPlatformMcpAdapter;
import ai.cc.chongming.review.infrastructure.notification.NotificationDeliveryRouter;
import ai.cc.chongming.review.infrastructure.notification.SmtpMailNotificationAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [AIREVIEW-PLAN-011#1.6] Ensures outbox commands reach the adapter matching their channel and
 * that disabled channels fail closed.
 *
 * @author wangli
 */
class NotificationDeliveryRouterTests {

    @Test
    void routesMailChannelToSmtpAdapter() {
        SmtpMailNotificationAdapter mailAdapter = mock(SmtpMailNotificationAdapter.class);
        when(mailAdapter.deliver(any())).thenReturn(new NotificationDeliveryReceipt("SMTP_ACCEPTED", "hash"));
        ObjectProvider<SmtpMailNotificationAdapter> mailProvider = provider(mailAdapter);
        NotificationDeliveryRouter router = new NotificationDeliveryRouter(mailProvider, provider(null));

        NotificationDeliveryReceipt receipt = router.deliver(command("smtp-mail", "reviewer@qq.com"));

        assertEquals("SMTP_ACCEPTED", receipt.responseCode());
        verify(mailAdapter).deliver(any());
    }

    @Test
    void rejectsMailChannelWhenSmtpIsDisabled() {
        NotificationDeliveryRouter router = new NotificationDeliveryRouter(provider(null), provider(null));

        NotificationDeliveryException exception = assertThrows(NotificationDeliveryException.class,
                () -> router.deliver(command("smtp-mail", "reviewer@qq.com")));

        assertEquals("MAIL_DISABLED", exception.code());
        assertEquals(false, exception.retryable());
    }

    @Test
    void routesOtherChannelsToLearningPlatformAdapter() {
        LearningPlatformMcpAdapter mcpAdapter = mock(LearningPlatformMcpAdapter.class);
        when(mcpAdapter.deliver(any())).thenReturn(new NotificationDeliveryReceipt("MCP_ACCEPTED", "hash"));
        NotificationDeliveryRouter router = new NotificationDeliveryRouter(provider(null), provider(mcpAdapter));

        NotificationDeliveryReceipt receipt = router.deliver(command("learning-platform", "recipient"));

        assertEquals("MCP_ACCEPTED", receipt.responseCode());
        verify(mcpAdapter).deliver(any());
    }

    @SuppressWarnings("unchecked")
    private static <T extends NotificationDeliveryPort> ObjectProvider<T> provider(T adapter) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        return provider;
    }

    private NotificationCommand command(String channel, String destination) {
        return new NotificationCommand(
                new ReviewId(UUID.randomUUID()), 1L, channel, destination,
                GateResult.PASS, "approved", List.of(), "/api/reviews/example/report");
    }
}
