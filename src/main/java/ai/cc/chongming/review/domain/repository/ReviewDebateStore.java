package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.JudgeDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import java.util.List;
import java.util.Optional;

/**
 * Stores immutable Claim/Turn facts and mutable topic state behind the debate application boundary.
 *
 * @author wangli
 */
public interface ReviewDebateStore {

    void saveClaim(Claim claim);

    Optional<Claim> findClaim(ReviewId reviewId, ClaimId claimId);

    List<Claim> findClaims(ReviewId reviewId);

    void saveTopic(DebateTopic topic);

    Optional<DebateTopic> findTopic(ReviewId reviewId, TopicId topicId);

    List<DebateTopic> findTopics(ReviewId reviewId);

    void saveTurn(ReviewId reviewId, DebateTurn turn);

    Optional<DebateTurn> findTurn(ReviewId reviewId, TurnId turnId);

    List<DebateTurn> findTurns(ReviewId reviewId, TopicId topicId);

    void saveJudgeDecision(ReviewId reviewId, JudgeDecision decision);

    Optional<JudgeDecision> findJudgeDecision(ReviewId reviewId, TopicId topicId);

    void saveGateDraft(GateDecision decision);

    Optional<GateDecision> findGateDraft(ReviewId reviewId);
}
