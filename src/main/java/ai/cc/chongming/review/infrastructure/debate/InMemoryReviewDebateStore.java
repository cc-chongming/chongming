package ai.cc.chongming.review.infrastructure.debate;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.JudgeDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * [AIREVIEW-PLAN-010#1.3] Deterministic process-local implementation used only when review
 * persistence is disabled; the MyBatis store takes over when it is enabled.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryReviewDebateStore implements ReviewDebateStore {

    private final Map<ReviewId, Map<ClaimId, Claim>> claims = new ConcurrentHashMap<>();
    private final Map<ReviewId, Map<TopicId, DebateTopic>> topics = new ConcurrentHashMap<>();
    private final Map<ReviewId, Map<TurnId, DebateTurn>> turns = new ConcurrentHashMap<>();
    private final Map<ReviewId, Map<TopicId, JudgeDecision>> judgeDecisions = new ConcurrentHashMap<>();
    private final Map<ReviewId, GateDecision> gateDrafts = new ConcurrentHashMap<>();

    @Override
    public void saveClaim(Claim claim) {
        claims.computeIfAbsent(claim.reviewId(), ignored -> new ConcurrentHashMap<>()).putIfAbsent(claim.claimId(), claim);
    }

    @Override
    public Optional<Claim> findClaim(ReviewId reviewId, ClaimId claimId) {
        return Optional.ofNullable(claims.getOrDefault(reviewId, Map.of()).get(claimId));
    }

    @Override
    public List<Claim> findClaims(ReviewId reviewId) {
        return claims.getOrDefault(reviewId, Map.of()).values().stream()
                .sorted(Comparator.comparing(claim -> claim.claimId().value()))
                .toList();
    }

    @Override
    public void saveTopic(DebateTopic topic) {
        topics.computeIfAbsent(topic.reviewId(), ignored -> new ConcurrentHashMap<>()).putIfAbsent(topic.id(), topic);
    }

    @Override
    public Optional<DebateTopic> findTopic(ReviewId reviewId, TopicId topicId) {
        return Optional.ofNullable(topics.getOrDefault(reviewId, Map.of()).get(topicId));
    }

    @Override
    public List<DebateTopic> findTopics(ReviewId reviewId) {
        return topics.getOrDefault(reviewId, Map.of()).values().stream()
                .sorted(Comparator.comparing(topic -> topic.id().value()))
                .toList();
    }

    @Override
    public void saveTurn(ReviewId reviewId, DebateTurn turn) {
        turns.computeIfAbsent(reviewId, ignored -> new ConcurrentHashMap<>()).putIfAbsent(turn.turnId(), turn);
    }

    @Override
    public Optional<DebateTurn> findTurn(ReviewId reviewId, TurnId turnId) {
        return Optional.ofNullable(turns.getOrDefault(reviewId, Map.of()).get(turnId));
    }

    @Override
    public List<DebateTurn> findTurns(ReviewId reviewId, TopicId topicId) {
        List<DebateTurn> values = new ArrayList<>();
        for (DebateTurn turn : turns.getOrDefault(reviewId, Map.of()).values()) {
            if (turn.topicId().equals(topicId)) {
                values.add(turn);
            }
        }
        values.sort(Comparator.comparingInt(DebateTurn::round).thenComparing(turn -> turn.turnId().value()));
        return List.copyOf(values);
    }

    @Override
    public List<DebateTurn> findTurns(ReviewId reviewId) {
        return turns.getOrDefault(reviewId, Map.of()).values().stream()
                .sorted(Comparator.comparing(DebateTurn::topicId, Comparator.comparing(TopicId::value))
                        .thenComparingInt(DebateTurn::round)
                        .thenComparing(turn -> turn.turnId().value()))
                .toList();
    }
    @Override
    public void saveJudgeDecision(ReviewId reviewId, JudgeDecision decision) {
        judgeDecisions.computeIfAbsent(reviewId, ignored -> new ConcurrentHashMap<>()).putIfAbsent(decision.topicId(), decision);
    }

    @Override
    public Optional<JudgeDecision> findJudgeDecision(ReviewId reviewId, TopicId topicId) {
        return Optional.ofNullable(judgeDecisions.getOrDefault(reviewId, Map.of()).get(topicId));
    }

    @Override
    public Map<TopicId, JudgeDecision> findJudgeDecisions(ReviewId reviewId) {
        return Map.copyOf(judgeDecisions.getOrDefault(reviewId, Map.of()));
    }
    @Override
    public void saveGateDraft(GateDecision decision) {
        gateDrafts.putIfAbsent(decision.reviewId(), decision);
    }

    @Override
    public Optional<GateDecision> findGateDraft(ReviewId reviewId) {
        return Optional.ofNullable(gateDrafts.get(reviewId));
    }
}
