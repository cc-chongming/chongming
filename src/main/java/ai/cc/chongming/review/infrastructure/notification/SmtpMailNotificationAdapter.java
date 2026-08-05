package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.application.NotificationDeliveryPort;
import ai.cc.chongming.review.config.NotificationMailProperties;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Function;

/**
 * [AIREVIEW-PLAN-011#1.6] Delivers final-Gate notifications over SMTP (QQ mailbox by default).
 *
 * <p>The authorization code is read from the configured environment variable at delivery time and
 * never appears in configuration files, logs, or receipts. Delivery fails closed when the sender
 * address or credential is unavailable.
 *
 * @author wangli
 */
@Component
@ConditionalOnProperty(prefix = "review.notification", name = "mail-enabled", havingValue = "true")
public class SmtpMailNotificationAdapter implements NotificationDeliveryPort {

    private final NotificationMailProperties properties;
    private final JavaMailSender mailSender;
    private final Function<String, String> environmentLookup;

    @org.springframework.beans.factory.annotation.Autowired
    public SmtpMailNotificationAdapter(NotificationMailProperties properties, JavaMailSender mailSender) {
        this(properties, mailSender, System::getenv);
    }

    public SmtpMailNotificationAdapter(
            NotificationMailProperties properties,
            JavaMailSender mailSender,
            Function<String, String> environmentLookup) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.mailSender = Objects.requireNonNull(mailSender, "mailSender must not be null");
        this.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup must not be null");
    }

    @Override
    public NotificationDeliveryReceipt deliver(NotificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (properties.sender().isBlank()) {
            throw new NotificationDeliveryException("MAIL_SENDER_UNCONFIGURED", false,
                    "SMTP sender address is not configured (review.notification.mail.sender)");
        }
        String credential = properties.authCode().isBlank()
                ? environmentLookup.apply(properties.credentialEnvironmentVariable())
                : properties.authCode();
        if (credential == null || credential.isBlank()) {
            throw new NotificationDeliveryException("MAIL_CREDENTIAL_UNAVAILABLE", false,
                    "SMTP authorization code is not available from its configured environment variable");
        }
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.sender());
            helper.setTo(command.destination());
            helper.setSubject(properties.subjectPrefix() + "Gate v" + command.gateVersion() + " · " + command.result());
            helper.setText(mailBody(command), false);
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException exception) {
            throw new NotificationDeliveryException("MAIL_MESSAGE_BUILD_ERROR", false,
                    "Unable to build the notification mail message", exception);
        } catch (MailAuthenticationException exception) {
            throw new NotificationDeliveryException("MAIL_AUTH_FAILED", false,
                    "SMTP authentication failed; verify the QQ mailbox and its authorization code", exception);
        } catch (MailException exception) {
            throw new NotificationDeliveryException("MAIL_TRANSPORT_ERROR", true,
                    "SMTP delivery failed", exception);
        }
        return new NotificationDeliveryReceipt("SMTP_ACCEPTED", sha256(command.idempotencyKey()));
    }

    private String mailBody(NotificationCommand command) {
        StringBuilder body = new StringBuilder();
        body.append("重明需求评审最终 Gate 通知\n\n");
        body.append("- 评审: ").append(command.reviewId().value()).append('\n');
        body.append("- Gate 版本: v").append(command.gateVersion()).append('\n');
        body.append("- 结论: ").append(command.result()).append('\n');
        body.append("- 理由: ").append(command.reason()).append('\n');
        if (!command.conditions().isEmpty()) {
            body.append("- 条件:\n");
            command.conditions().forEach(condition -> body.append("  1. ").append(condition).append('\n'));
        }
        body.append("- 报告接口: ").append(command.reportUrl()).append('\n');
        body.append("- 幂等键: ").append(command.idempotencyKey()).append('\n');
        return body.toString();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte digestByte : digest) {
                hex.append(String.format("%02x", digestByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
