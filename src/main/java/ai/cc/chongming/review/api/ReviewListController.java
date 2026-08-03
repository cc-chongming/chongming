package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewListQueryService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AIREVIEW-PLAN-021#8] Lists review and report projections without changing the existing per-review routes.
 *
 * @author zyj
 */
@RestController
@RequestMapping("/api")
public class ReviewListController {

    private final ReviewListQueryService reviewListQueryService;

    public ReviewListController(ReviewListQueryService reviewListQueryService) {
        this.reviewListQueryService = Objects.requireNonNull(reviewListQueryService, "reviewListQueryService must not be null");
    }

    @GetMapping("/reviews")
    public ReviewListQueryService.ReviewPage reviews(
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "hasReport", required = false) Boolean hasReport,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return reviewListQueryService.findPage(stage, hasReport, page, size);
    }

    @GetMapping("/reports")
    public ReviewListQueryService.ReportPage reports(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return reviewListQueryService.findReports(page, size);
    }
}
