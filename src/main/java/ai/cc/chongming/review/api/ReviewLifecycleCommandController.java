package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewCommandService;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * [AIREVIEW-PLAN-010#1.6,#1.7] Exposes idempotent review start, safe cancellation and isolated retry commands.
 *
 * <p>Authentication is not introduced here: until the security integration is enabled, {@code userId} follows the same caller-supplied
 * identity convention as the existing review intake endpoint.
 *
 * @author wangli
 */
@Validated
@RestController
@RequestMapping("/api/reviews")
public class ReviewLifecycleCommandController {

    private final ReviewCommandService reviewCommandService;

    public ReviewLifecycleCommandController(ReviewCommandService reviewCommandService) {
        this.reviewCommandService = reviewCommandService;
    }

    /**
     * Accepts a first-attempt director launch and returns before model execution completes.
     */
    @PostMapping("/{reviewId}/start")
    public ResponseEntity<ReviewCommandService.StartReviewResult> start(
            @PathVariable UUID reviewId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @Valid @RequestBody StartReviewRequest request) {
        String effectiveTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        ReviewCommandService.StartReviewResult result = reviewCommandService.start(
                new ReviewId(reviewId),
                new ReviewCommandService.StartReviewCommand(
                        request.expectedVersion(),
                        idempotencyKey,
                        request.userId(),
                        effectiveTraceId,
                        request.publicTasks(),
                        request.changeReason(),
                        request.initialMessage()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /**
     * Interrupts a live runtime when present, then returns the final cancellation transition.
     */
    @PostMapping("/{reviewId}/cancel")
    public Mono<ResponseEntity<ReviewCommandService.CancelReviewResult>> cancel(
            @PathVariable UUID reviewId,
            @RequestParam @Min(0) long expectedVersion) {
        return reviewCommandService.cancel(new ReviewId(reviewId), expectedVersion).map(ResponseEntity::ok);
    }

    /**
     * Creates a clean pending attempt. The caller then invokes {@code /start} with the new version.
     */
    @PostMapping("/{reviewId}/retry")
    public ResponseEntity<ReviewCommandService.RetryReviewResult> retry(
            @PathVariable UUID reviewId,
            @RequestParam @Min(0) long expectedVersion) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(reviewCommandService.retry(new ReviewId(reviewId), expectedVersion));
    }

    /**
     * Public start payload. Runtime identifiers are deliberately server-derived.
     *
     * @author wangli
     */
    public record StartReviewRequest(
            @Min(0) long expectedVersion,
            @NotBlank String userId,
            @NotEmpty List<@NotBlank String> publicTasks,
            @NotBlank String changeReason,
            @NotBlank String initialMessage) {
    }
}
