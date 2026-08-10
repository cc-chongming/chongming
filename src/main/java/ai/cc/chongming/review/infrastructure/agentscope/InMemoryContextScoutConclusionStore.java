package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * [AIREVIEW-PLAN-023#5] Process-local Scout conclusion store used when durable persistence is disabled.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryContextScoutConclusionStore implements ContextScoutConclusionStore {

    private final Map<ConclusionKey, ContextScoutConclusion> conclusions = new ConcurrentHashMap<>();

    @Override
    public void save(ContextScoutConclusion conclusion) {
        Objects.requireNonNull(conclusion, "conclusion must not be null");
        conclusions.put(new ConclusionKey(conclusion.reviewId(), conclusion.attemptNo()), conclusion);
    }

    @Override
    public Optional<ContextScoutConclusion> find(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return Optional.ofNullable(conclusions.get(new ConclusionKey(reviewId, attemptNo)));
    }

    private record ConclusionKey(ReviewId reviewId, int attemptNo) {
    }
}
