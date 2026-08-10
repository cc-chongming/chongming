package ai.cc.chongming.review.infrastructure.assessment;

import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.ReviewAssessmentStore;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-024#方案0] Process-local assessment store used while durable persistence is disabled.
 * Mirrors the conditional-wiring precedent of the in-memory Context Scout conclusion store; the MySQL
 * counterpart takes over when {@code review.persistence.enabled=true}.
 *
 * <p>Idempotency semantics frozen by {@code ReviewAssessmentContractTests}: a batch must not contain
 * duplicate (role, checkpointKey) pairs, and a later batch replaces the previous assessment for the
 * same review, attempt, role and checkpointKey (latest submission wins).
 *
 * @author wangli
 */
@Component
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryReviewAssessmentStore implements ReviewAssessmentStore {

    private static final Comparator<ReviewAssessment> DETERMINISTIC_ORDER =
            Comparator.comparing((ReviewAssessment assessment) -> assessment.roleType().name())
                    .thenComparing(ReviewAssessment::checkpointKey);

    private final Map<BatchKey, Map<String, ReviewAssessment>> assessmentsByReview = new ConcurrentHashMap<>();

    @Override
    public void saveBatch(ReviewId reviewId, int attemptNo, Collection<ReviewAssessment> assessments) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(assessments, "assessments must not be null");
        if (assessments.isEmpty()) {
            throw new IllegalArgumentException("assessments must not be empty");
        }
        Set<String> batchKeys = new HashSet<>();
        for (ReviewAssessment assessment : assessments) {
            Objects.requireNonNull(assessment, "assessment must not be null");
            if (!reviewId.equals(assessment.reviewId()) || attemptNo != assessment.attemptNo()) {
                throw new IllegalArgumentException(
                        "assessment does not belong to review " + reviewId.value() + " attempt " + attemptNo);
            }
            if (!batchKeys.add(assessment.storageKey())) {
                throw new IllegalArgumentException("duplicate assessment in batch: " + assessment.storageKey());
            }
        }
        Map<String, ReviewAssessment> stored =
                assessmentsByReview.computeIfAbsent(new BatchKey(reviewId, attemptNo), key -> new ConcurrentHashMap<>());
        for (ReviewAssessment assessment : assessments) {
            stored.put(assessment.storageKey(), assessment);
        }
    }

    @Override
    public List<ReviewAssessment> findByReview(ReviewId reviewId, int attemptNo) {
        Map<String, ReviewAssessment> stored = storedAssessments(reviewId, attemptNo);
        return stored.values().stream().sorted(DETERMINISTIC_ORDER).toList();
    }

    @Override
    public List<ReviewAssessment> findByReview(ReviewId reviewId, int attemptNo, RoleType roleType) {
        Objects.requireNonNull(roleType, "roleType must not be null");
        return findByReview(reviewId, attemptNo).stream()
                .filter(assessment -> assessment.roleType() == roleType)
                .toList();
    }

    private Map<String, ReviewAssessment> storedAssessments(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        return assessmentsByReview.getOrDefault(new BatchKey(reviewId, attemptNo), Map.of());
    }

    private record BatchKey(ReviewId reviewId, int attemptNo) {
    }
}
