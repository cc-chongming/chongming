package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewReportService;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import org.springframework.http.MediaType;
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
 * [AIREVIEW-PLAN-011#1.4] Exposes versioned public JSON and Markdown report snapshots.
 *
 * @author wangli
 */
@RestController
@RequestMapping("/api/reviews/{reviewId}/report")
public class ReviewReportController {

    private final ReviewReportService reportService;
    private final ReviewRegistry reviewRegistry;

    public ReviewReportController(ReviewReportService reportService, ReviewRegistry reviewRegistry) {
        this.reportService = reportService;
        this.reviewRegistry = reviewRegistry;
    }

    /**
     * Generates a fresh immutable report version. It is mainly a retry hook when automatic generation failed.
     */
    @PostMapping
    public ResponseEntity<ReviewReportService.ReportVersionView> generate(@PathVariable UUID reviewId) {
        Review review = findReview(reviewId);
        ReviewReport report = reportService.generate(review);
        return ResponseEntity.status(201).body(new ReviewReportService.ReportVersionView(
                report.reportVersion(), report.gateVersion(), report.contentHash(), report.createdAt().toString()));
    }

    @GetMapping
    public ResponseEntity<String> get(
            @PathVariable UUID reviewId,
            @RequestParam(value = "version", required = false) Long version,
            @RequestParam(value = "format", defaultValue = "json") String format) {
        return reportService.find(new ReviewId(reviewId), version)
                .map(report -> toResponse(report, format))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/versions")
    public List<ReviewReportService.ReportVersionView> versions(@PathVariable UUID reviewId) {
        return reportService.findVersions(new ReviewId(reviewId));
    }

    private Review findReview(UUID reviewId) {
        return reviewRegistry.find(new ReviewId(reviewId))
                .orElseThrow(() -> new java.util.NoSuchElementException("review does not exist"));
    }

    private ResponseEntity<String> toResponse(ReviewReport report, String format) {
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(report.contentJson());
        }
        if ("markdown".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .body(report.markdown());
        }
        throw new IllegalArgumentException("format must be json or markdown");
    }
}
