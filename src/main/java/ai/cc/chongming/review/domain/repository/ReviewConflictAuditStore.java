package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.ReviewConflictAudit;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * [AIREVIEW-PLAN-024#方案5] Batch persistence boundary for deterministic conflict audit facts.
 *
 * @author zyj
 */
public interface ReviewConflictAuditStore {

    void replaceBatch(ReviewId reviewId, int attemptNo, Collection<ReviewConflictAudit> records);

    void finalizeAttempt(
            ReviewId reviewId,
            int attemptNo,
            Collection<String> registeredSubjectKeys,
            Instant updatedAt);

    List<ReviewConflictAudit> findByReviewAttempt(ReviewId reviewId, int attemptNo);
}
