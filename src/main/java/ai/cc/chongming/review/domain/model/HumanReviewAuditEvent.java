package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.1] Immutable audit fact for a human review draft mutation.
 *
 * @author wangli
 */
public record HumanReviewAuditEvent(
        UUID auditId,
        ReviewId reviewId,
        UUID itemId,
        Action action,
        String actorId,
        long beforeVersion,
        long afterVersion,
        Instant occurredAt) {

    public HumanReviewAuditEvent {
        Objects.requireNonNull(auditId, "auditId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        if (beforeVersion < -1 || afterVersion < 0) {
            throw new IllegalArgumentException("audit versions are invalid");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    /**
     * @author wangli
     */
    public enum Action {
        CREATED,
        UPDATED,
        DELETED
    }
}
