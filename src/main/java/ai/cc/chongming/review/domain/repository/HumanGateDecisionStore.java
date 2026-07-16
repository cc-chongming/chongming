package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.util.List;
import java.util.Optional;

/**
 * [AIREVIEW-PLAN-011#1.3] Append-only storage boundary for final human Gate versions.
 *
 * @author wangli
 */
public interface HumanGateDecisionStore {

    void append(HumanGateDecision decision);

    Optional<HumanGateDecision> findLatest(ReviewId reviewId);

    List<HumanGateDecision> findVersions(ReviewId reviewId);
}
