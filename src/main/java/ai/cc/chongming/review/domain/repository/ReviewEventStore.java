package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import java.util.List;
import java.util.Optional;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

/**
 * [AIREVIEW-PLAN-010#1.2] Appends and replays globally sequenced business events for one review.
 *
 * @author wangli
 */
public interface ReviewEventStore {

    ReviewEvent append(ReviewEventDraft draft);

    List<ReviewEvent> findAfter(ReviewId reviewId, long afterSequence, int limit);

    Optional<ReviewEvent> findLatest(ReviewId reviewId);

    /**
     * Returns the newest fact of one type without replaying the whole review timeline.
     */
    Optional<ReviewEvent> findLatestByType(ReviewId reviewId, ReviewEventType eventType);

    /**
     * Returns the newest fact of one type for one review attempt.
     */
    Optional<ReviewEvent> findLatestByTypeAndAttempt(ReviewId reviewId, ReviewEventType eventType, int attemptNo);

    /**
     * Returns recent review facts across reviews for platform-level read models.
     * The per-review replay contract remains unchanged.
     */
    List<ReviewEvent> findRecentAcrossReviews(int limit);

    /**
     * Returns at most one latest fact per review for review list and dashboard projections.
     */
    List<ReviewEvent> findLatestAcrossReviews(int limit);
}
