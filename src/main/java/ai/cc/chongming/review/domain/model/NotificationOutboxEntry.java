package ai.cc.chongming.review.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.5] Versioned delivery state for one idempotent notification command.
 *
 * @author wangli
 */
public record NotificationOutboxEntry(
        UUID notificationId,
        NotificationCommand command,
        String requestHash,
        DeliveryStatus deliveryStatus,
        int attemptCount,
        Instant nextRetryAt,
        Instant deliveredAt,
        String responseCode,
        String responseHash,
        String lastErrorCode,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public NotificationOutboxEntry {
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        requestHash = requireText(requestHash, "requestHash");
        Objects.requireNonNull(deliveryStatus, "deliveryStatus must not be null");
        if (attemptCount < 0 || version < 0) {
            throw new IllegalArgumentException("attemptCount and version must not be negative");
        }
        Objects.requireNonNull(nextRetryAt, "nextRetryAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (deliveryStatus == DeliveryStatus.SENT && (deliveredAt == null || responseCode == null || responseCode.isBlank())) {
            throw new IllegalArgumentException("sent notification must include deliveredAt and responseCode");
        }
        if (deliveryStatus != DeliveryStatus.SENT && deliveredAt != null) {
            throw new IllegalArgumentException("only sent notification may have deliveredAt");
        }
    }

    public static NotificationOutboxEntry pending(NotificationCommand command, String requestHash, Instant now) {
        return new NotificationOutboxEntry(
                UUID.randomUUID(), command, requestHash, DeliveryStatus.PENDING, 0, now,
                null, null, null, null, 0L, now, now);
    }

    public NotificationOutboxEntry claim(long expectedVersion, Instant now) {
        requireVersion(expectedVersion);
        if (deliveryStatus != DeliveryStatus.PENDING && deliveryStatus != DeliveryStatus.FAILED) {
            throw new IllegalStateException("only pending or failed notification can be claimed");
        }
        if (nextRetryAt.isAfter(now)) {
            throw new IllegalStateException("notification retry is not due");
        }
        return copy(DeliveryStatus.SENDING, attemptCount, nextRetryAt, null, null, null, null, version + 1, now);
    }

    public NotificationOutboxEntry markSent(
            long expectedVersion, NotificationDeliveryReceipt receipt, Instant now) {
        requireSending(expectedVersion);
        Objects.requireNonNull(receipt, "receipt must not be null");
        return copy(DeliveryStatus.SENT, attemptCount + 1, nextRetryAt, now,
                receipt.responseCode(), receipt.responseHash(), null, version + 1, now);
    }

    public NotificationOutboxEntry markFailed(
            long expectedVersion, String errorCode, boolean dead, Instant retryAt, Instant now) {
        requireSending(expectedVersion);
        return copy(dead ? DeliveryStatus.DEAD : DeliveryStatus.FAILED, attemptCount + 1,
                Objects.requireNonNull(retryAt, "retryAt must not be null"), null,
                null, null, requireText(errorCode, "errorCode"), version + 1, now);
    }

    public NotificationOutboxEntry retryNow(long expectedVersion, Instant now) {
        requireVersion(expectedVersion);
        if (deliveryStatus != DeliveryStatus.FAILED && deliveryStatus != DeliveryStatus.DEAD) {
            throw new IllegalStateException("only failed or dead notification can be retried");
        }
        return copy(DeliveryStatus.PENDING, attemptCount, now, null, null, null, null, version + 1, now);
    }

    private NotificationOutboxEntry copy(
            DeliveryStatus status,
            int attempts,
            Instant retryAt,
            Instant delivered,
            String code,
            String responseDigest,
            String errorCode,
            long nextVersion,
            Instant now) {
        return new NotificationOutboxEntry(notificationId, command, requestHash, status, attempts, retryAt,
                delivered, code, responseDigest, errorCode, nextVersion, createdAt, now);
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new IllegalStateException("expectedVersion does not match notification version");
        }
    }

    private void requireSending(long expectedVersion) {
        requireVersion(expectedVersion);
        if (deliveryStatus != DeliveryStatus.SENDING) {
            throw new IllegalStateException("only sending notification can receive a delivery result");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** @author wangli */
    public enum DeliveryStatus {
        PENDING,
        SENDING,
        SENT,
        FAILED,
        DEAD
    }
}
