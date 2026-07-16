package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.NotificationOutboxService;
import ai.cc.chongming.review.domain.model.NotificationOutboxEntry;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.5,#1.7] Exposes notification state and reviewer-authorized idempotent retry commands.
 *
 * @author wangli
 */
@RestController
@RequestMapping("/api/reviews/{reviewId}/notifications")
public class NotificationOutboxController {

    private final NotificationOutboxService outboxService;
    private final ReviewRegistry reviewRegistry;
    private final ReviewerIdentityProvider identityProvider;

    public NotificationOutboxController(
            NotificationOutboxService outboxService,
            ReviewRegistry reviewRegistry,
            ReviewerIdentityProvider identityProvider) {
        this.outboxService = outboxService;
        this.reviewRegistry = reviewRegistry;
        this.identityProvider = identityProvider;
    }

    @GetMapping
    public List<NotificationOutboxEntry> list(@PathVariable UUID reviewId) {
        requireReview(reviewId);
        return outboxService.findByReview(new ReviewId(reviewId));
    }

    @PostMapping("/{notificationId}/retry")
    public ResponseEntity<NotificationOutboxEntry> retry(
            @PathVariable UUID reviewId,
            @PathVariable UUID notificationId,
            @RequestParam long expectedVersion) {
        requireReview(reviewId);
        ReviewerIdentityProvider.ReviewerIdentity reviewer = identityProvider.currentReviewer();
        if (reviewer == null || !reviewer.canReview()) {
            throw new SecurityException("current identity is not allowed to retry notifications");
        }
        return ResponseEntity.ok(outboxService.retryNow(
                new ReviewId(reviewId), notificationId, expectedVersion, reviewer.reviewerId()));
    }

    private void requireReview(UUID reviewId) {
        if (reviewRegistry.find(new ReviewId(reviewId)).isEmpty()) {
            throw new java.util.NoSuchElementException("review does not exist");
        }
    }
}
