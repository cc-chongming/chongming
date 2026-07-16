package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewReportService;
import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [AIREVIEW-PLAN-011#1.4] HTTP contract tests for JSON, Markdown and versioned report reads.
 *
 * @author wangli
 */
class ReviewReportControllerTests {

    private ReviewReportService reportService;
    private MockMvc mockMvc;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        reportService = mock(ReviewReportService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReviewReportController(reportService, mock(ReviewRegistry.class)))
                .setControllerAdvice(new ReviewReportExceptionHandler())
                .build();
        reviewId = UUID.randomUUID();
    }

    @Test
    void returnsStoredJsonAndMarkdownFormats() throws Exception {
        ReviewReport report = report();
        when(reportService.find(new ReviewId(reviewId), null)).thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/reviews/{reviewId}/report", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.stage").value("NOTIFYING"));
        mockMvc.perform(get("/api/reviews/{reviewId}/report", reviewId).param("format", "markdown"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string("# 审核报告\n"));
    }

    @Test
    void returnsVersionMetadataAndNotFoundForMissingReport() throws Exception {
        when(reportService.findVersions(new ReviewId(reviewId))).thenReturn(List.of(
                new ReviewReportService.ReportVersionView(1L, 2L, "a".repeat(64), "2026-07-16T08:00:00Z")));
        when(reportService.find(new ReviewId(reviewId), 2L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/reviews/{reviewId}/report/versions", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].gateVersion").value(2));
        mockMvc.perform(get("/api/reviews/{reviewId}/report", reviewId).param("version", "2"))
                .andExpect(status().isNotFound());
    }

    private ReviewReport report() {
        return new ReviewReport(
                UUID.randomUUID(), new ReviewId(reviewId), 1L, 1L, "a".repeat(64),
                "{\"summary\":{\"stage\":\"NOTIFYING\"}}", "# 审核报告\n", Instant.parse("2026-07-16T08:00:00Z"));
    }
}
