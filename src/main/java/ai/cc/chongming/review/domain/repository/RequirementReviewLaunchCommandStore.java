package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.util.Objects;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-023#3] Atomically reserves requirement-scoped launch idempotency commands.
 *
 * @author zyj
 */
public interface RequirementReviewLaunchCommandStore {

    Reservation reserve(RequirementId requirementId, String idempotencyKey, String requestFingerprint, UUID ownerToken);

    boolean renew(RequirementId requirementId, String idempotencyKey, UUID ownerToken);

    boolean complete(
            RequirementId requirementId,
            String idempotencyKey,
            String requestFingerprint,
            UUID ownerToken,
            ReviewId reviewId);

    void release(RequirementId requirementId, String idempotencyKey, UUID ownerToken);

    boolean invalidateCompleted(
            RequirementId requirementId,
            String idempotencyKey,
            String requestFingerprint,
            ReviewId reviewId);

    /**
     * [AIREVIEW-PLAN-023#3] Outcome of one atomic reservation attempt.
     *
     * @author zyj
     */
    record Reservation(ReservationStatus status, ReviewId reviewId) {

        public Reservation {
            Objects.requireNonNull(status, "status must not be null");
            if (status == ReservationStatus.REPLAY && reviewId == null) {
                throw new IllegalArgumentException("replay reservation must reference a review");
            }
        }

        public static Reservation acquired() {
            return new Reservation(ReservationStatus.ACQUIRED, null);
        }

        public static Reservation inProgress() {
            return new Reservation(ReservationStatus.IN_PROGRESS, null);
        }

        public static Reservation conflict() {
            return new Reservation(ReservationStatus.CONFLICT, null);
        }

        public static Reservation replay(ReviewId reviewId) {
            return new Reservation(ReservationStatus.REPLAY, reviewId);
        }
    }

    enum ReservationStatus {
        ACQUIRED,
        IN_PROGRESS,
        REPLAY,
        CONFLICT
    }
}
