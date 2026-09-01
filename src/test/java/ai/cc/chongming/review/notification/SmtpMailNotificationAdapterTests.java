package ai.cc.chongming.review.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.config.NotificationMailProperties;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.notification.SmtpMailNotificationAdapter;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * [AIREVIEW-PLAN-108] Also verifies the multipart/alternative body: the plain-text fallback keeps
 * the historical content while the HTML part carries the branded header, CTA and escaped values.
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
        assertTrue(sent.getContentType().startsWith("multipart/alternative"), "CT=" + sent.getContentType());
        String plain = partBody(sent, "text/plain");
        assertTrue(plain.contains("Gate 版本: v1"));
        assertTrue(plain.contains("结论: CONDITIONAL"));
        assertTrue(plain.contains("补充 version 字段"));
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

    @Test
    void sendsTransitionNotificationAsMultipartAlternativeWithBrandedHtml() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties("smtp.qq.com", 465, "sender@qq.com", AUTH_CODE_ENV,
                        "【重明需求评审】", null, "http://example.org/review"),
                mailSender,
                env -> AUTH_CODE_ENV.equals(env) ? "test-auth-code" : null);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        NotificationCommand command = NotificationCommand.forEvent(
                reviewId, "TASK_HANDOFF", 1L, "smtp-mail", "reviewer@qq.com", "wangli",
                "task-handoff", "需求评审任务已交接给评审角色 A",
                "订单列表联调任务", "订单列表查询需求", "DEVELOPING", "wangli");

        adapter.deliver(command);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        assertTrue(sent.getContentType().startsWith("multipart/alternative"));
        String plain = partBody(sent, "text/plain");
        assertTrue(plain.contains("事件: TASK_HANDOFF"));
        // [AIREVIEW-PLAN-109] Task info card rows in the plain-text fallback.
        assertTrue(plain.contains("- 任务: 订单列表联调任务"));
        assertTrue(plain.contains("- 需求: 订单列表查询需求"));
        assertTrue(plain.contains("- 当前状态: 开发中"));
        assertTrue(plain.contains("- 当前持有人: wangli"));
        String html = partBody(sent, "text/html");
        assertTrue(html.contains("重明"));
        assertTrue(html.contains("AI 需求评审平台"));
        assertTrue(html.contains("任务流转"));
        // [AIREVIEW-PLAN-109] Task info card rows in the branded HTML.
        assertTrue(html.contains(">任务</td>"));
        assertTrue(html.contains(">订单列表联调任务</td>"));
        assertTrue(html.contains(">需求</td>"));
        assertTrue(html.contains(">订单列表查询需求</td>"));
        assertTrue(html.contains(">当前状态</td>"));
        assertTrue(html.contains(">开发中</td>"));
        assertTrue(html.contains(">当前持有人</td>"));
        assertTrue(html.contains(">wangli</td>"));
        // [AIREVIEW-PLAN-109] Hash-history deep link: /review/#/reviews/{id}/report.
        assertTrue(html.contains("href=\"http://example.org/review/#/reviews/" + reviewId.value() + "/report\""));
        assertTrue(html.contains("查看评审报告"));
    }

    @Test
    void escapesHtmlInHtmlPartButKeepsPlainText() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties(null, 0, "sender@qq.com", AUTH_CODE_ENV, null),
                mailSender,
                env -> "test-auth-code");
        String malicious = "<script>alert(1)</script>";
        NotificationCommand command = NotificationCommand.forEvent(
                new ReviewId(UUID.randomUUID()), "TASK_HANDOFF", 1L, "smtp-mail", "reviewer@qq.com", null,
                "task-handoff", malicious, null, null, null, null);

        adapter.deliver(command);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        String html = partBody(sent, "text/html");
        assertFalse(html.contains(malicious));
        assertTrue(html.contains("&lt;script&gt;"));
        String plain = partBody(sent, "text/plain");
        assertTrue(plain.contains(malicious));
    }

    @Test
    void rendersGateResultBadgeInHtml() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties(null, 0, "sender@qq.com", AUTH_CODE_ENV, null),
                mailSender,
                env -> "test-auth-code");

        adapter.deliver(command("smtp-mail", "reviewer@qq.com"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        String html = partBody(sent, "text/html");
        assertTrue(html.contains("最终 Gate 通知"));
        assertTrue(html.contains("有条件通过"));
    }

    @Test
    void fallsBackToReportUrlWhenPublicBaseUrlIsBlank() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        SmtpMailNotificationAdapter adapter = new SmtpMailNotificationAdapter(
                new NotificationMailProperties(null, 0, "sender@qq.com", AUTH_CODE_ENV, null),
                mailSender,
                env -> "test-auth-code");

        adapter.deliver(command("smtp-mail", "reviewer@qq.com"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        String html = partBody(sent, "text/html");
        assertTrue(html.contains("href=\"/api/reviews/example/report\""));
    }

    private NotificationCommand command(String channel, String destination) {
        return new NotificationCommand(
                new ReviewId(UUID.randomUUID()), 1L, channel, destination,
                GateResult.CONDITIONAL, "needs tracked conditions",
                List.of("补充 version 字段"), "/api/reviews/example/report");
    }

    private static String describe(Multipart multipart) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            builder.append("[").append(i).append("]=").append(part.getContentType());
            if (part.getContent() instanceof Multipart nested) {
                builder.append("(").append(describe(nested)).append(")");
            }
        }
        return builder.toString();
    }

    private static String partBody(MimeMessage message, String contentTypePrefix) throws Exception {
        Object content = message.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.getContentType().toLowerCase(Locale.ROOT).startsWith(contentTypePrefix)) {
                    return (String) part.getContent();
                }
            }
            throw new AssertionError("No " + contentTypePrefix + " part; top CT=" + message.getContentType()
                    + " parts=" + describe(multipart));
        }
        throw new AssertionError("No " + contentTypePrefix + " part; content=" + content);
    }
}