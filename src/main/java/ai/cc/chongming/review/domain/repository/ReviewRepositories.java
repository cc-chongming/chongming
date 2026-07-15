package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-003#1.6] Defines bulk-loading and optimistic-lock contracts without persistence annotations.
 *
 * @author wangli
 */
public interface ReviewRepositories {

    Optional<Review> findReview(ReviewId reviewId);

    void saveReview(Review review, long expectedVersion);

    Map<ClaimId, Claim> findClaimsByIds(ReviewId reviewId, Set<ClaimId> claimIds);

    Map<EvidenceId, EvidenceReference> findEvidenceByIds(ReviewId reviewId, Set<EvidenceId> evidenceIds);

    Map<TurnId, DebateTurn> findTurnsByIds(ReviewId reviewId, Set<TurnId> turnIds);

    Optional<String> findCommandResult(ReviewId reviewId, IdempotencyKey idempotencyKey);
}

