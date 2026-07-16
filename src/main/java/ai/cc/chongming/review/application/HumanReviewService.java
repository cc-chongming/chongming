package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.HumanReviewAuditEvent;
import ai.cc.chongming.review.domain.model.HumanReviewItem;
import ai.cc.chongming.review.domain.model.HumanReviewItem.DraftContent;
import ai.cc.chongming.review.domain.model.HumanReviewItem.ItemStatus;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.HumanReviewItemStore;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.1][AIREVIEW-PLAN-011#1.2] Applies auditable human-review draft CRUD in WAITING_HUMAN only.
 *
 * @author wangli
 */
@Service
public class HumanReviewService {

    private final HumanReviewItemStore itemStore;
    private final ReviewerIdentityProvider identityProvider;
    private final ReviewEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public HumanReviewService(
            HumanReviewItemStore itemStore,
            ReviewerIdentityProvider identityProvider,
            ReviewEventPublisher eventPublisher) {
        this(itemStore, identityProvider, eventPublisher, Clock.systemUTC());
    }

    HumanReviewService(
            HumanReviewItemStore itemStore,
            ReviewerIdentityProvider identityProvider,
            ReviewEventPublisher eventPublisher,
            Clock clock) {
        this.itemStore = Objects.requireNonNull(itemStore, "itemStore must not be null");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public synchronized HumanReviewItem create(Review review, DraftContent content) {
        ReviewerIdentity reviewer = requireEditableReview(review);
        Instant now = clock.instant();
        HumanReviewItem item = HumanReviewItem.draft(review.id(), reviewer.reviewerId(), content, now);
        itemStore.save(item);
        appendAudit(item, reviewer, HumanReviewAuditEvent.Action.CREATED, -1L, item.version(), now);
        publish(review, item, ReviewEventType.HUMAN_REVIEW_ITEM_CREATED);
        return item;
    }

    public synchronized HumanReviewItem update(
            Review review,
            UUID itemId,
            long expectedVersion,
            DraftContent content) {
        ReviewerIdentity reviewer = requireEditableReview(review);
        HumanReviewItem existing = requireItem(review, itemId);
        Instant now = clock.instant();
        HumanReviewItem revised = existing.revise(content, expectedVersion, now);
        itemStore.save(revised);
        appendAudit(revised, reviewer, HumanReviewAuditEvent.Action.UPDATED, existing.version(), revised.version(), now);
        publish(review, revised, ReviewEventType.HUMAN_REVIEW_ITEM_UPDATED);
        return revised;
    }

    public synchronized HumanReviewItem delete(Review review, UUID itemId, long expectedVersion) {
        ReviewerIdentity reviewer = requireEditableReview(review);
        HumanReviewItem existing = requireItem(review, itemId);
        Instant now = clock.instant();
        HumanReviewItem deleted = existing.delete(expectedVersion, now);
        itemStore.save(deleted);
        appendAudit(deleted, reviewer, HumanReviewAuditEvent.Action.DELETED, existing.version(), deleted.version(), now);
        publish(review, deleted, ReviewEventType.HUMAN_REVIEW_ITEM_DELETED);
        return deleted;
    }

    public List<HumanReviewItem> findDrafts(Review review, ClaimSeverity severity) {
        Objects.requireNonNull(review, "review must not be null");
        return itemStore.findByReview(review.id(), ItemStatus.DRAFT, severity);
    }

    public List<HumanReviewAuditEvent> auditTrail(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        return itemStore.findAuditByReview(review.id());
    }

    private ReviewerIdentity requireEditableReview(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.stage() != ReviewStage.WAITING_HUMAN) {
            throw new IllegalStateException("human review drafts are editable only in WAITING_HUMAN");
        }
        ReviewerIdentity reviewer = identityProvider.currentReviewer();
        if (reviewer == null || !reviewer.canReview()) {
            throw new SecurityException("current identity is not allowed to review");
        }
        return reviewer;
    }

    private HumanReviewItem requireItem(Review review, UUID itemId) {
        return itemStore.find(review.id(), itemId)
                .orElseThrow(() -> new java.util.NoSuchElementException("human review item does not belong to this review"));
    }

    private void appendAudit(
            HumanReviewItem item,
            ReviewerIdentity reviewer,
            HumanReviewAuditEvent.Action action,
            long beforeVersion,
            long afterVersion,
            Instant now) {
        itemStore.appendAudit(new HumanReviewAuditEvent(
                UUID.randomUUID(),
                item.reviewId(),
                item.itemId(),
                action,
                reviewer.reviewerId(),
                beforeVersion,
                afterVersion,
                now));
    }

    private void publish(Review review, HumanReviewItem item, ReviewEventType type) {
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                type,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                90,
                Map.of("itemId", item.itemId().toString(), "version", Long.toString(item.version()))));
    }
}
