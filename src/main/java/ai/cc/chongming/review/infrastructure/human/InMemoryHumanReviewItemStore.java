package ai.cc.chongming.review.infrastructure.human;

import ai.cc.chongming.review.domain.model.HumanReviewAuditEvent;
import ai.cc.chongming.review.domain.model.HumanReviewItem;
import ai.cc.chongming.review.domain.model.HumanReviewItem.ItemStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.HumanReviewItemStore;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [AIREVIEW-PLAN-011#1.1] Process-local draft/audit store used until the PLAN-011 MyBatis writer is enabled.
 *
 * @author wangli
 */
@Repository
public class InMemoryHumanReviewItemStore implements HumanReviewItemStore {

    private final Map<ReviewId, Map<UUID, HumanReviewItem>> itemsByReview = new ConcurrentHashMap<>();
    private final Map<ReviewId, List<HumanReviewAuditEvent>> auditByReview = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(HumanReviewItem item) {
        itemsByReview.computeIfAbsent(item.reviewId(), ignored -> new ConcurrentHashMap<>())
                .compute(item.itemId(), (ignored, existing) -> {
                    if (existing != null && existing.version() >= item.version()) {
                        throw new IllegalStateException("human review item version is stale");
                    }
                    return item;
                });
    }

    @Override
    public Optional<HumanReviewItem> find(ReviewId reviewId, UUID itemId) {
        return Optional.ofNullable(itemsByReview.getOrDefault(reviewId, Map.of()).get(itemId));
    }

    @Override
    public List<HumanReviewItem> findByReview(ReviewId reviewId, ItemStatus status, ClaimSeverity severity) {
        return itemsByReview.getOrDefault(reviewId, Map.of()).values().stream()
                .filter(item -> status == null || item.status() == status)
                .filter(item -> severity == null || item.severity() == severity)
                .sorted(Comparator.comparing(HumanReviewItem::createdAt).thenComparing(HumanReviewItem::itemId))
                .toList();
    }

    @Override
    public synchronized void appendAudit(HumanReviewAuditEvent event) {
        auditByReview.computeIfAbsent(event.reviewId(), ignored -> new ArrayList<>()).add(event);
    }

    @Override
    public List<HumanReviewAuditEvent> findAuditByReview(ReviewId reviewId) {
        return List.copyOf(auditByReview.getOrDefault(reviewId, List.of()));
    }
}
