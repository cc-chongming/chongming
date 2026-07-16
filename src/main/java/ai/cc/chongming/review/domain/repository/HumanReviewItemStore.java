package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.HumanReviewAuditEvent;
import ai.cc.chongming.review.domain.model.HumanReviewItem;
import ai.cc.chongming.review.domain.model.HumanReviewItem.ItemStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.1] Persistence boundary for versioned human review drafts and their audit trail.
 *
 * @author wangli
 */
public interface HumanReviewItemStore {

    void save(HumanReviewItem item);

    Optional<HumanReviewItem> find(ReviewId reviewId, UUID itemId);

    List<HumanReviewItem> findByReview(ReviewId reviewId, ItemStatus status, ClaimSeverity severity);

    void appendAudit(HumanReviewAuditEvent event);

    List<HumanReviewAuditEvent> findAuditByReview(ReviewId reviewId);
}
