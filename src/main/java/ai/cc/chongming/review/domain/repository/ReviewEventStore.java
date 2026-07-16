package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
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
}
