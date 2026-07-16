package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.HumanReviewService;
import ai.cc.chongming.review.domain.model.HumanReviewItem;
import ai.cc.chongming.review.domain.model.HumanReviewItem.DraftContent;
import ai.cc.chongming.review.domain.model.HumanReviewItem.ItemType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.2] HTTP CRUD contract for human review drafts in a review scope.
 *
 * @author wangli
 */
@RestController
@RequestMapping("/api/reviews/{reviewId}/human-review-items")
public class HumanReviewController {

    private final HumanReviewService humanReviewService;
    private final ReviewRegistry reviewRegistry;

    public HumanReviewController(HumanReviewService humanReviewService, ReviewRegistry reviewRegistry) {
        this.humanReviewService = humanReviewService;
        this.reviewRegistry = reviewRegistry;
    }

    @GetMapping
    public List<HumanReviewItem> findDrafts(
            @PathVariable UUID reviewId,
            @RequestParam(value = "severity", required = false) ClaimSeverity severity) {
        return humanReviewService.findDrafts(requireReview(reviewId), severity);
    }

    @PostMapping
    public ResponseEntity<HumanReviewItem> create(
            @PathVariable UUID reviewId,
            @RequestBody DraftRequest request) {
        HumanReviewItem item = humanReviewService.create(requireReview(reviewId), request.toContent());
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    @PatchMapping("/{itemId}")
    public HumanReviewItem update(
            @PathVariable UUID reviewId,
            @PathVariable UUID itemId,
            @RequestParam long expectedVersion,
            @RequestBody DraftRequest request) {
        return humanReviewService.update(requireReview(reviewId), itemId, expectedVersion, request.toContent());
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID reviewId,
            @PathVariable UUID itemId,
            @RequestParam long expectedVersion) {
        humanReviewService.delete(requireReview(reviewId), itemId, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    private Review requireReview(UUID reviewId) {
        return reviewRegistry.find(new ReviewId(reviewId))
                .orElseThrow(() -> new NoSuchElementException("review was not found"));
    }

    /**
     * @author wangli
     */
    public record DraftRequest(
            ItemType type,
            ClaimSeverity severity,
            String title,
            String content,
            List<UUID> claimIds,
            List<UUID> evidenceIds,
            String action) {

        DraftContent toContent() {
            List<EvidenceId> references = evidenceIds == null ? List.of() : evidenceIds.stream().map(EvidenceId::new).toList();
            return new DraftContent(type, severity, title, content, claimIds, references, action);
        }
    }
}
