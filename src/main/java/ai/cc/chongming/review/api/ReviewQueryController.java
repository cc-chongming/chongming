package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewQueryService;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * [AIREVIEW-PLAN-010#1.3] Exposes public review read models without accepting source file paths.
 *
 * @author wangli
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewQueryController {

    private final ReviewQueryService reviewQueryService;

    public ReviewQueryController(ReviewQueryService reviewQueryService) {
        this.reviewQueryService = reviewQueryService;
    }

    @GetMapping("/{reviewId}")
    public ResponseEntity<ReviewQueryService.ReviewSummary> summary(@PathVariable UUID reviewId) {
        return reviewQueryService.findSummary(new ReviewId(reviewId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{reviewId}/plans")
    public ReviewQueryService.EventPage plans(
            @PathVariable UUID reviewId,
            @RequestParam(value = "afterSequence", defaultValue = "0") long afterSequence,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return reviewQueryService.findPlans(new ReviewId(reviewId), afterSequence, limit);
    }

    @GetMapping("/{reviewId}/debates")
    public java.util.List<ReviewQueryService.DebateView> debates(@PathVariable UUID reviewId) {
        return reviewQueryService.findDebates(new ReviewId(reviewId));
    }

    @GetMapping("/{reviewId}/claims")
    public java.util.List<ReviewQueryService.ClaimView> claims(@PathVariable UUID reviewId) {
        return reviewQueryService.findClaims(new ReviewId(reviewId));
    }

    @GetMapping("/{reviewId}/evidence/{evidenceId}")
    public ResponseEntity<ReviewQueryService.EvidenceView> evidence(
            @PathVariable UUID reviewId,
            @PathVariable UUID evidenceId) {
        return reviewQueryService.findEvidence(new ReviewId(reviewId), new EvidenceId(evidenceId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
