package ai.cc.chongming.review.infrastructure.human;

import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [AIREVIEW-PLAN-011#1.3] Process-local append-only final Gate history.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryHumanGateDecisionStore implements HumanGateDecisionStore {

    private final Map<ReviewId, List<HumanGateDecision>> decisions = new ConcurrentHashMap<>();

    @Override
    public synchronized void append(HumanGateDecision decision) {
        List<HumanGateDecision> versions = decisions.computeIfAbsent(decision.reviewId(), ignored -> new ArrayList<>());
        if (!versions.isEmpty() && versions.getLast().gateVersion() >= decision.gateVersion()) {
            throw new IllegalStateException("human Gate version is stale");
        }
        versions.add(decision);
    }

    @Override
    public Optional<HumanGateDecision> findLatest(ReviewId reviewId) {
        List<HumanGateDecision> versions = decisions.get(reviewId);
        return versions == null || versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast());
    }

    @Override
    public List<HumanGateDecision> findVersions(ReviewId reviewId) {
        return List.copyOf(decisions.getOrDefault(reviewId, List.of()));
    }
}
