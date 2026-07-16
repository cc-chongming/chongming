package ai.cc.chongming.review.human;

import ai.cc.chongming.review.application.HumanReviewService;
import ai.cc.chongming.review.application.ReviewEventService;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.HumanReviewAuditEvent;
import ai.cc.chongming.review.domain.model.HumanReviewItem;
import ai.cc.chongming.review.domain.model.HumanReviewItem.DraftContent;
import ai.cc.chongming.review.domain.model.HumanReviewItem.ItemType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.Permission;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import ai.cc.chongming.review.infrastructure.human.InMemoryHumanReviewItemStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [AIREVIEW-PLAN-011#1.1][AIREVIEW-PLAN-011#1.2] Verifies draft lifecycle, version checks and audit facts.
 *
 * @author wangli
 */
class HumanReviewServiceTests {

    @Test
    void createsUpdatesAndSoftDeletesAuditableDraftsInWaitingHuman() {
        Fixture fixture = new Fixture(reviewer("reviewer-1"));
        Review review = waitingHumanReview(fixture.reviewId);

        HumanReviewItem created = fixture.service.create(review, content(ClaimSeverity.P1, "Missing authorization"));
        HumanReviewItem updated = fixture.service.update(
                review,
                created.itemId(),
                0L,
                content(ClaimSeverity.P0, "Privilege escalation"));
        HumanReviewItem deleted = fixture.service.delete(review, created.itemId(), 1L);

        assertEquals(1L, updated.version());
        assertEquals(HumanReviewItem.ItemStatus.DELETED, deleted.status());
        assertTrue(fixture.service.findDrafts(review, null).isEmpty());
        assertEquals(
                List.of(HumanReviewAuditEvent.Action.CREATED, HumanReviewAuditEvent.Action.UPDATED,
                        HumanReviewAuditEvent.Action.DELETED),
                fixture.service.auditTrail(review).stream().map(HumanReviewAuditEvent::action).toList());
        assertEquals(
                List.of(ReviewEventType.HUMAN_REVIEW_ITEM_CREATED, ReviewEventType.HUMAN_REVIEW_ITEM_UPDATED,
                        ReviewEventType.HUMAN_REVIEW_ITEM_DELETED),
                fixture.events.replay(fixture.reviewId, 0L, 10).stream().map(event -> event.type()).toList());
    }

    @Test
    void rejectsStaleVersionAndAnyEditOutsideWaitingHuman() {
        Fixture fixture = new Fixture(reviewer("reviewer-1"));
        Review review = waitingHumanReview(fixture.reviewId);
        HumanReviewItem item = fixture.service.create(review, content(ClaimSeverity.P2, "Clarify retry policy"));
        fixture.service.update(review, item.itemId(), 0L, content(ClaimSeverity.P2, "Clarify timeout policy"));

        assertThrows(IllegalStateException.class,
                () -> fixture.service.update(review, item.itemId(), 0L, content(ClaimSeverity.P2, "stale update")));
        Review pending = Review.pending(new ReviewId(UUID.randomUUID()));
        assertThrows(IllegalStateException.class,
                () -> fixture.service.create(pending, content(ClaimSeverity.P3, "outside human stage")));
    }

    @Test
    void requiresReviewerPermissionAndSupportsSeverityFilter() {
        Fixture fixture = new Fixture(reviewer("reviewer-1"));
        Review review = waitingHumanReview(fixture.reviewId);
        fixture.service.create(review, content(ClaimSeverity.P0, "blocking issue"));
        fixture.service.create(review, content(ClaimSeverity.P2, "minor issue"));

        assertEquals(1, fixture.service.findDrafts(review, ClaimSeverity.P0).size());
        HumanReviewService denied = new HumanReviewService(
                fixture.store,
                () -> new ReviewerIdentity("viewer", Set.of()),
                fixture.events);
        assertThrows(SecurityException.class,
                () -> denied.create(review, content(ClaimSeverity.P1, "not permitted")));
    }

    private Review waitingHumanReview(ReviewId reviewId) {
        return Review.restore(reviewId, ReviewStage.WAITING_HUMAN, 1, 0L, List.of(), Map.of());
    }

    private DraftContent content(ClaimSeverity severity, String title) {
        return new DraftContent(ItemType.RISK, severity, title, "Public rationale for " + title,
                List.of(UUID.randomUUID()), List.of(), "Require remediation");
    }

    private ReviewerIdentityProvider reviewer(String reviewerId) {
        return () -> new ReviewerIdentity(reviewerId, Set.of(Permission.REVIEW));
    }

    private static final class Fixture {
        private final ReviewId reviewId = new ReviewId(UUID.randomUUID());
        private final InMemoryHumanReviewItemStore store = new InMemoryHumanReviewItemStore();
        private final ReviewEventService events = new ReviewEventService(new InMemoryReviewEventStore());
        private final HumanReviewService service;

        private Fixture(ReviewerIdentityProvider identityProvider) {
            service = new HumanReviewService(store, identityProvider, events);
        }
    }
}
