package ai.cc.chongming.review.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.config.NotificationMailProperties;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.notification.SmtpMailNotificationAdapter;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [AIREVIEW-PLAN-011#1.6] Verifies the QQ/SMTP channel builds the Gate mail from the immutable
 * command and fails closed without credentials instead of guessing a transport request.
 *
 * @author wangli
 */
class SmtpMailNotificationAdapterTests {

    private static final String AUTH_CODE_ENV = "REVIEW_TEST_QQ_AUTH_CODE";

    @Test
    void sendsGateMailToDestinationAndReturnsSanitizedReceipt() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties("smtp.qq.com", 465, "sender@qq.com", AUTH_CODE_ENV, "【重明需求评审】"),
                mailSender,
                env -> AUTH_CODE_ENV.equals(env) ? "test-auth-code" : null);

        NotificationDeliveryReceipt receipt = adapter.deliver(command("smtp-mail", "reviewer@qq.com"));

        assertEquals("SMTP_ACCEPTED", receipt.responseCode());
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        assertEquals("sender@qq.com", sent.getFrom()[0].toString());
        assertEquals("reviewer@qq.com", sent.getRecipients(Message.RecipientType.TO)[0].toString());
        assertEquals("【重明需求评审】Gate v1 · CONDITIONAL", MimeUtility.decodeText(sent.getSubject()));
        String body = (String) sent.getContent();
        assertTrue(body.contains("Gate 版本: v1"));
        assertTrue(body.contains("结论: CONDITIONAL"));
        assertTrue(body.contains("补充 version 字段"));
    }

    @Test
    void prefersAuthCodeFromLocalConfigurationOverEnvironment() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties(null, 0, "sender@qq.com", AUTH_CODE_ENV, null, "local-auth-code"),
                mailSender,
                env -> null);

        NotificationDeliveryReceipt receipt = adapter.deliver(command("smtp-mail", "reviewer@qq.com"));

        assertEquals("SMTP_ACCEPTED", receipt.responseCode());
        verify(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }

    @Test
    void failsClosedWhenAuthorizationCodeIsUnavailable() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties(null, 0, "sender@qq.com", AUTH_CODE_ENV, null),
                mailSender,
                env -> null);

        NotificationDeliveryException exception = assertThrows(NotificationDeliveryException.class,
                () -> adapter.deliver(command("smtp-mail", "reviewer@qq.com")));

        assertEquals("MAIL_CREDENTIAL_UNAVAILABLE", exception.code());
        assertEquals(false, exception.retryable());
        verify(mailSender, never()).send((MimeMessage) org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failsClosedWhenSenderAddressIsMissing() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties(null, 0, "", AUTH_CODE_ENV, null),
                mailSender,
                env -> "test-auth-code");

        NotificationDeliveryException exception = assertThrows(NotificationDeliveryException.class,
                () -> adapter.deliver(command("smtp-mail", "reviewer@qq.com")));

        assertEquals("MAIL_SENDER_UNCONFIGURED", exception.code());
        assertEquals(false, exception.retryable());
    }

    @Test
    void mapsAuthenticationFailureToNonRetryableError() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailAuthenticationException("535 bad credentials")).when(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties(null, 0, "sender@qq.com", AUTH_CODE_ENV, null),
                mailSender,
                env -> "test-auth-code");

        NotificationDeliveryException exception = assertThrows(NotificationDeliveryException.class,
                () -> adapter.deliver(command("smtp-mail", "reviewer@qq.com")));

        assertEquals("MAIL_AUTH_FAILED", exception.code());
        assertEquals(false, exception.retryable());
    }

    @Test
    void mapsTransportFailureToRetryableError() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("connection reset")).when(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties(null, 0, "sender@qq.com", AUTH_CODE_ENV, null),
                mailSender,
                env -> "test-auth-code");

        NotificationDeliveryException exception = assertThrows(NotificationDeliveryException.class,
                () -> adapter.deliver(command("smtp-mail", "reviewer@qq.com")));

        assertEquals("MAIL_TRANSPORT_ERROR", exception.code());
        assertEquals(true, exception.retryable());
    }

    private NotificationCommand command(String channel, String destination) {
        return new NotificationCommand(
                new ReviewId(UUID.randomUUID()), 1L, channel, destination,
                GateResult.CONDITIONAL, "needs tracked conditions",
                List.of("补充 version 字段"), "/api/reviews/example/report");
    }
}
