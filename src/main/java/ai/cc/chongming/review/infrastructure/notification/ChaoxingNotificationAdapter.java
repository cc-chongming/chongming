package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.application.NotificationDeliveryPort;
import ai.cc.chongming.review.config.ChaoxingNotificationProperties;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import ai.cc.chongming.review.application.NotificationDeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-030] Delivers a notification to a single Chaoxing (学习通) uid. The outbox entry's
 * {@code destination} carries the recipient uid; the sender ({@code puid}/{@code pcode}) and the
 * signing key come from {@code review.notification.chaoxing.*}. Only active when the channel is
 * explicitly enabled, so the QQ-mail path is unaffected otherwise.
 *
 * @author wangli
 */
@Component
@ConditionalOnProperty(prefix = "review.notification.chaoxing", name = "enabled", havingValue = "true")
public class ChaoxingNotificationAdapter implements NotificationDeliveryPort {

    private final ChaoxingNotificationProperties properties;
    private final ChaoxingNoticeClient client;

    public ChaoxingNotificationAdapter(
            ChaoxingNotificationProperties properties, ChaoxingNoticeClient client) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public NotificationDeliveryReceipt deliver(NotificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Integer uid = parseUid(command.destination());
        String title = command.templateKey() != null
                ? command.reason()
                : "重明需求评审 Gate v" + command.gateVersion() + " · " + command.result();
        String content = buildContent(command);
        ChaoxingNoticeClient.NoticeResult result =
                client.sendNotice(List.of(uid), title, content, command.reportUrl());
        if (result.result() != 1) {
            throw new NotificationDeliveryException("CHAOXING_REJECTED", false,
                    "Chaoxing notice rejected: " + result.msg());
        }
        return new NotificationDeliveryReceipt(
                "CHAOXING_ACCEPTED", sha256(command.idempotencyKey()));
    }

    private static Integer parseUid(String destination) {
        try {
            return Integer.valueOf(destination.trim());
        } catch (RuntimeException exception) {
            throw new NotificationDeliveryException("CHAOXING_UID_INVALID", false,
                    "Chaoxing destination is not a numeric uid: " + destination);
        }
    }

    private static String buildContent(NotificationCommand command) {
        StringBuilder body = new StringBuilder();
        if (command.templateKey() != null) {
            body.append("重明任务流转通知\n");
            body.append("事件: ").append(command.eventType()).append('\n');
            body.append("内容: ").append(command.reason()).append('\n');
            body.append("收件人: ").append(command.recipientUsername() == null ? "-" : command.recipientUsername());
            return body.toString();
        }
        body.append("重明需求评审最终 Gate 通知\n");
        body.append("评审: ").append(command.reviewId().value()).append('\n');
        body.append("Gate 版本: v").append(command.gateVersion()).append('\n');
        body.append("结论: ").append(command.result()).append('\n');
        body.append("理由: ").append(command.reason());
        return body.toString();
    }

    private static String sha256(String value) {
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
