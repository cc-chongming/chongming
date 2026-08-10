package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.util.Optional;

/**
 * [AIREVIEW-PLAN-023#5] Attempt-scoped persistence boundary for public Context Scout conclusions.
 *
 * @author zyj
 */
public interface ContextScoutConclusionStore {

    void save(ContextScoutConclusion conclusion);

    Optional<ContextScoutConclusion> find(ReviewId reviewId, int attemptNo);
}
