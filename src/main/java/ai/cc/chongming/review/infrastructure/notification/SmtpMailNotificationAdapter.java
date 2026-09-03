package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.application.NotificationDeliveryPort;
import ai.cc.chongming.review.config.NotificationMailProperties;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * [AIREVIEW-PLAN-011#1.6] Delivers final-Gate notifications over SMTP (QQ mailbox by default).
 * [AIREVIEW-PLAN-108] Mail body is now multipart/alternative: the existing plain-text body stays as a
 * fallback while a branded HTML body (brand header + status badge + info card + CTA + footer) is the
 * primary rendering for mainstream mail clients.
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

    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpMailNotificationAdapter.class);

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
            helper.setSubject(command.templateKey() != null
                    ? properties.subjectPrefix() + command.reason()
                    : properties.subjectPrefix() + "Gate v" + command.gateVersion() + " · " + command.result());
            // [AIREVIEW-PLAN-108] Top-level multipart/alternative (plain-text fallback + branded HTML).
            // Spring's MimeMessageHelper.setText(text, html) requires its multipart mode, which wraps the
            // alternatives in a multipart/mixed root; this attachment-free mail builds the alternative
            // container directly so the content type is exactly multipart/alternative.
            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart plainPart = new MimeBodyPart();
            plainPart.setText(mailBody(command), StandardCharsets.UTF_8.name(), "plain");
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setText(buildHtml(command), StandardCharsets.UTF_8.name(), "html");
            alternative.addBodyPart(plainPart);
            alternative.addBodyPart(htmlPart);
            message.setContent(alternative);
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException exception) {
            throw new NotificationDeliveryException("MAIL_MESSAGE_BUILD_ERROR", false,
                    "Unable to build the notification mail message", exception);
        } catch (MailAuthenticationException exception) {
            // Surface the SMTP reply (e.g. QQ 535) without ever logging credentials.
            Throwable root = exception;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            LOGGER.warn("SMTP authentication failed for sender {}: {} / {}",
                    properties.sender(), exception.getMessage(), root.getMessage());
            throw new NotificationDeliveryException("MAIL_AUTH_FAILED", false,
                    "SMTP authentication failed; verify the QQ mailbox and its authorization code", exception);
        } catch (MailException exception) {
            LOGGER.warn("SMTP transport error for sender {}: {}", properties.sender(), exception.getMessage());
            throw new NotificationDeliveryException("MAIL_TRANSPORT_ERROR", true,
                    "SMTP delivery failed", exception);
        }
        return new NotificationDeliveryReceipt("SMTP_ACCEPTED", sha256(command.idempotencyKey()));
    }

    private String mailBody(NotificationCommand command) {
        StringBuilder body = new StringBuilder();
        if (command.templateKey() != null) {
            // [AIREVIEW-PLAN-030] Matrix transition notification.
            body.append("重明任务流转通知\n\n");
            body.append("- 事件: ").append(command.eventType()).append('\n');
            body.append("- 内容: ").append(command.reason()).append('\n');
            body.append("- 收件人: ").append(command.recipientUsername() == null ? "-" : command.recipientUsername()).append('\n');
            body.append("- 评审: ").append(command.reviewId().value()).append('\n');
            // [AIREVIEW-PLAN-109] Task info card rows; only present when the command carries them.
            if (command.objectTitle() != null) {
                body.append("- 任务: ").append(command.objectTitle()).append('\n');
            }
            if (command.objectSubtitle() != null) {
                body.append("- 需求: ").append(command.objectSubtitle()).append('\n');
            }
            if (command.objectStatus() != null) {
                body.append("- 当前状态: ").append(statusLabel(command.objectStatus())).append('\n');
            }
            if (command.objectHolder() != null) {
                body.append("- 当前持有人: ").append(command.objectHolder()).append('\n');
            }
            if (requirementUrl(command) != null) {
                body.append("- 需求详情: ").append(requirementUrl(command)).append('\n');
            }
            // [AIREVIEW-PLAN-114#1] 纯文本兜底同步给出工作台深链。
            if (properties.publicBaseUrl() != null && !properties.publicBaseUrl().isBlank()) {
                body.append("- 工作台: ").append(properties.publicBaseUrl())
                        .append("/#/reviews/").append(command.reviewId().value()).append("/live\n");
            }
            body.append("- 报告接口: ").append(command.reportUrl()).append('\n');
            body.append("- 幂等键: ").append(command.idempotencyKey()).append('\n');
            return body.toString();
        }
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

    private String buildHtml(NotificationCommand command) {
        boolean matrix = command.templateKey() != null;
        String eventType = command.eventType() == null ? "" : command.eventType();
        String title;
        String badgeText;
        String badgeBackground;
        String badgeColor;
        if (matrix) {
            if ("TASK_HANDOFF".equals(eventType)) {
                badgeBackground = "#dbeafe";
                badgeColor = "#1d4ed8";
                badgeText = "任务流转";
                title = command.reason();
            } else if ("HUMAN_REVIEW_REQUIRED".equals(eventType)) {
                badgeBackground = "#fef3c7";
                badgeColor = "#92400e";
                badgeText = "待人工决策";
                title = "AI 评审完成，待人工决策";
            } else {
                badgeBackground = "#e5e7eb";
                badgeColor = "#374151";
                badgeText = eventType;
                title = command.reason();
            }
        } else {
            title = "最终 Gate 通知";
            switch (command.result()) {
                case PASS, AI_PASS -> {
                    badgeBackground = "#dcfce7";
                    badgeColor = "#166534";
                    badgeText = "通过";
                }
                case CONDITIONAL -> {
                    badgeBackground = "#fef3c7";
                    badgeColor = "#92400e";
                    badgeText = "有条件通过";
                }
                case BLOCK -> {
                    badgeBackground = "#fee2e2";
                    badgeColor = "#991b1b";
                    badgeText = "驳回";
                }
                case HUMAN_REQUIRED -> {
                    badgeBackground = "#fef3c7";
                    badgeColor = "#92400e";
                    badgeText = "需人工决策";
                }
                default -> {
                    badgeBackground = "#e5e7eb";
                    badgeColor = "#374151";
                    badgeText = command.result().name();
                }
            }
        }

        StringBuilder rows = new StringBuilder();
        if (matrix) {
            rows.append(infoRow("事件", eventType));
            rows.append(infoRow("内容", command.reason()));
            rows.append(infoRow("收件人", command.recipientUsername() == null ? "-" : command.recipientUsername()));
            rows.append(infoRow("评审", command.reviewId().value().toString()));
            // [AIREVIEW-PLAN-109] Task info card rows; only present when the command carries them.
            if (command.objectTitle() != null) {
                rows.append(infoRow("任务", command.objectTitle()));
            }
            if (command.objectSubtitle() != null) {
                rows.append(infoRow("需求", command.objectSubtitle()));
            }
            if (command.objectStatus() != null) {
                rows.append(infoRow("当前状态", statusLabel(command.objectStatus())));
            }
            if (command.objectHolder() != null) {
                rows.append(infoRow("当前持有人", command.objectHolder()));
            }
        } else {
            rows.append(infoRow("评审", command.reviewId().value().toString()));
            rows.append(infoRow("Gate 版本", "v" + command.gateVersion()));
            rows.append(infoRow("结论", command.result().name()));
            rows.append(infoRow("理由", command.reason()));
            if (!command.conditions().isEmpty()) {
                rows.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\"><tr>"
                        + "<td width=\"88\" valign=\"top\" style=\"font-size:13px;color:#6b7280;padding:4px 0;\">条件</td>"
                        + "<td style=\"font-size:14px;color:#111827;padding:4px 0;\">" + conditionsHtml(command.conditions()) + "</td>"
                        + "</tr></table>");
            }
        }

        // [AIREVIEW-PLAN-114#1] 报告仅在最终结论后生成：待人工决策/任务类邮件的 CTA 落实时
        // 工作台（人工决策邮件落人工决策视图），避免点开「尚无报告」空页；Gate 邮件才落报告页。
        boolean hasPublicBase = properties.publicBaseUrl() != null && !properties.publicBaseUrl().isBlank();
        boolean gateMail = command.templateKey() == null;
        String ctaHref;
        String ctaText;
        if (!gateMail && hasPublicBase) {
            ctaHref = properties.publicBaseUrl() + "/#/reviews/" + command.reviewId().value() + "/live";
            ctaText = "HUMAN_REVIEW_REQUIRED".equals(command.eventType()) ? "进入人工决策" : "查看评审工作台";
        } else {
            ctaHref = hasPublicBase
                    ? properties.publicBaseUrl() + "/#/reviews/" + command.reviewId().value() + "/report"
                    : command.reportUrl();
            ctaText = "查看评审报告";
        }
        String requirementUrl = requirementUrl(command);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <title>").append(escapeHtml(title)).append("</title>\n");
        html.append("</head>\n");
        html.append("<body style=\"margin:0;padding:0;background-color:#f4f5f7;\">\n");
        html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"background-color:#f4f5f7;\">\n");
        html.append("  <tr>\n");
        html.append("    <td align=\"center\" style=\"background-color:#f4f5f7;padding:24px;\">\n");
        html.append("      <table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"640\" style=\"background-color:#ffffff;border-radius:8px;\">\n");
        html.append("        <tr>\n");
        html.append("          <td bgcolor=\"#1e3a8a\" style=\"background-color:#1e3a8a;padding:16px 24px;border-radius:8px 8px 0 0;\">\n");
        html.append("            <span style=\"color:#ffffff;font-weight:700;font-size:18px;\">重明</span>\n");
        html.append("            <span style=\"color:#bfdbfe;font-size:13px;margin-left:8px;\">AI 需求评审平台</span>\n");
        html.append("          </td>\n");
        html.append("        </tr>\n");
        html.append("        <tr>\n");
        html.append("          <td style=\"padding:24px 24px 16px;\">\n");
        html.append("            <h1 style=\"margin:0 0 12px;font-size:18px;color:#111827;line-height:1.5;\">").append(escapeHtml(title)).append("</h1>\n");
        html.append("            <span style=\"display:inline-block;padding:4px 12px;border-radius:999px;font-size:12px;font-weight:700;background-color:").append(badgeBackground).append(";color:").append(badgeColor).append(";\">").append(escapeHtml(badgeText)).append("</span>\n");
        html.append("          </td>\n");
        html.append("        </tr>\n");
        html.append("        <tr>\n");
        html.append("          <td style=\"padding:0 24px;\">").append(rows).append("</td>\n");
        html.append("        </tr>\n");
        html.append("        <tr>\n");
        html.append("          <td style=\"padding:24px;\">\n");
        html.append("            <a href=\"").append(escapeHtml(ctaHref)).append("\" style=\"display:inline-block;background-color:#2563eb;color:#ffffff;padding:10px 22px;border-radius:6px;text-decoration:none;font-weight:700;font-size:14px;\">").append(escapeHtml(ctaText)).append("</a>\n");
        if (requirementUrl != null) {
            // [AIREVIEW-PLAN-110#1] 次按钮：需求详情页哈希深链。
            html.append("            <a href=\"").append(escapeHtml(requirementUrl)).append("\" style=\"display:inline-block;margin-left:10px;background-color:#ffffff;color:#2563eb;border:1px solid #2563eb;padding:9px 22px;border-radius:6px;text-decoration:none;font-weight:700;font-size:14px;\">查看需求详情</a>\n");
        }
        html.append("          </td>\n");
        html.append("        </tr>\n");
        html.append("        <tr>\n");
        html.append("          <td style=\"padding:16px 24px 24px;border-top:1px solid #e5e7eb;\">\n");
        html.append("            <div style=\"font-size:12px;color:#9ca3af;line-height:1.8;\">\n");
        html.append("              报告接口：").append(escapeHtml(command.reportUrl())).append("<br/>\n");
        html.append("              幂等键：").append(escapeHtml(command.idempotencyKey())).append("<br/>\n");
        html.append("              本邮件由重明评审系统自动发送，请勿直接回复。\n");
        html.append("            </div>\n");
        html.append("          </td>\n");
        html.append("        </tr>\n");
        html.append("      </table>\n");
        html.append("    </td>\n");
        html.append("  </tr>\n");
        html.append("</table>\n");
        html.append("</body>\n");
        html.append("</html>");
        return html.toString();
    }

    /**
     * [AIREVIEW-PLAN-109] Chinese label for the task status shown in the mail card; unknown
     * statuses (e.g. statuses added later) fall through to the raw enum name.
     */
    /** [AIREVIEW-PLAN-110#1] 需求详情页哈希深链；publicBaseUrl 或需求 id 缺失时为 null。 */
    private String requirementUrl(NotificationCommand command) {
        if (command.requirementId() == null
                || properties.publicBaseUrl() == null || properties.publicBaseUrl().isBlank()) {
            return null;
        }
        return properties.publicBaseUrl() + "/#/requirements/" + command.requirementId();
    }

    private static String statusLabel(String status) {
        // [AIREVIEW-PLAN-109#3] 与 DevTaskTypes.DevTaskStatus 枚举一一对应的中文映射。
        return switch (status) {
            case "PENDING_ASSIGN" -> "待指派";
            case "DEVELOPING" -> "开发中";
            case "PAUSED" -> "已暂停";
            case "PENDING_ACCEPTANCE" -> "待验收";
            case "DONE" -> "已完成";
            case "CANCELLED" -> "已关闭";
            default -> status;
        };
    }

    private String infoRow(String label, String value) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\"><tr>"
                + "<td width=\"88\" valign=\"top\" style=\"font-size:13px;color:#6b7280;padding:4px 0;\">" + escapeHtml(label) + "</td>"
                + "<td style=\"font-size:14px;color:#111827;padding:4px 0;\">" + escapeHtml(value) + "</td>"
                + "</tr></table>";
    }

    private String conditionsHtml(List<String> conditions) {
        StringBuilder html = new StringBuilder();
        int index = 1;
        for (String condition : conditions) {
            html.append("<div style=\"padding:1px 0;\">").append(index++).append(". ").append(escapeHtml(condition)).append("</div>");
        }
        return html.toString();
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
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