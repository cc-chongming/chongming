package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

/**
 * [AIREVIEW-PLAN-021#2][REQLIFE-H1] Atomically reserves the one transition that removes a review from PENDING.
 *
 * @author zyj
 */
@FunctionalInterface
public interface ReviewStartReservationStore {

    boolean claimStartFromPending(ReviewId reviewId, long expectedVersion, int attemptNo, long nextVersion);

    static ReviewStartReservationStore noop() {
        return (reviewId, expectedVersion, attemptNo, nextVersion) -> true;
    }
}
