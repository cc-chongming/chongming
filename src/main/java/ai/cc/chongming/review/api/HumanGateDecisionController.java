package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.HumanGateDecisionService;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.3] Exposes append-only submission and reading of human final Gate versions.
 *
 * @author wangli
 */
@RestController
@RequestMapping("/api/reviews/{reviewId}/human-gate-decisions")
public class HumanGateDecisionController {

    private final HumanGateDecisionService decisionService;
    private final ReviewRegistry reviewRegistry;

    public HumanGateDecisionController(HumanGateDecisionService decisionService, ReviewRegistry reviewRegistry) {
        this.decisionService = decisionService;
        this.reviewRegistry = reviewRegistry;
    }

    @PostMapping
    public ResponseEntity<HumanGateDecision> finalizeDecision(
            @PathVariable UUID reviewId,
            @RequestBody FinalDecisionRequest request) {
        HumanGateDecision decision = decisionService.finalizeDecision(requireReview(reviewId), request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(decision);
    }

    @GetMapping
    public List<HumanGateDecision> versions(@PathVariable UUID reviewId) {
        return decisionService.findVersions(requireReview(reviewId));
    }

    private Review requireReview(UUID reviewId) {
        return reviewRegistry.find(new ReviewId(reviewId))
                .orElseThrow(() -> new NoSuchElementException("review was not found"));
    }

    /**
     * @author wangli
     */
    public record FinalDecisionRequest(
            long expectedVersion,
            GateResult result,
            String reason,
            List<String> conditions,
            String overrideReason) {

        HumanGateDecisionService.FinalDecisionCommand toCommand() {
            return new HumanGateDecisionService.FinalDecisionCommand(
                    expectedVersion, result, reason, conditions, overrideReason);
        }
    }
}
