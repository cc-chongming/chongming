package ai.cc.chongming.review.report;

import ai.cc.chongming.review.application.ReviewQueryService;
import ai.cc.chongming.review.application.ReviewReportService;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.human.InMemoryHumanGateDecisionStore;
import ai.cc.chongming.review.infrastructure.human.InMemoryHumanReviewItemStore;
import ai.cc.chongming.review.infrastructure.report.InMemoryReviewReportStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [AIREVIEW-PLAN-011#1.4] Verifies immutable public report creation after a final human Gate.
 *
 * @author wangli
 */
class ReviewReportServiceTests {

    private final ReviewId reviewId = new ReviewId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private final ReviewQueryService queryService = mock(ReviewQueryService.class);
    private final InMemoryReviewReportStore reportStore = new InMemoryReviewReportStore();
    private final InMemoryHumanGateDecisionStore decisionStore = new InMemoryHumanGateDecisionStore();
    private final InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
    private ReviewReportService service;
    private Review review;

    @BeforeEach
    void setUp() {
        review = Review.restore(reviewId, ReviewStage.NOTIFYING, 1, 5L, List.of(), Map.of());
        registry.register(review);
        decisionStore.append(new HumanGateDecision(
                reviewId, 1L, GateResult.PASS, "approved for release", List.of(), null,
                "reviewer-1", null, Instant.parse("2026-07-16T08:00:00Z")));
        when(queryService.findSummary(reviewId)).thenReturn(java.util.Optional.of(new ReviewQueryService.ReviewSummary(
                reviewId.value(), 1, "NOTIFYING", 95, 9L, 5L, "2026-07-16 16:00:00",
                new ReviewQueryService.GateView("PASS", "FINAL", "HUMAN", "approved for release",
                        "2026-07-16 16:00:00"),
                null)));
        when(queryService.findPlans(reviewId, 0L, 500)).thenReturn(new ReviewQueryService.EventPage(List.of(), null));
        when(queryService.findDebates(reviewId)).thenReturn(List.of());
        service = new ReviewReportService(
                reportStore,
                queryService,
                new InMemoryHumanReviewItemStore(),
                decisionStore,
                registry,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-16T08:01:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createsVersionedJsonAndMarkdownWithoutInternalReasoning() throws java.io.IOException {
        ReviewReport first = service.generate(review);
        ReviewReport second = service.generate(review);

        assertEquals(1L, first.reportVersion());
        assertEquals(2L, second.reportVersion());
        assertEquals(first.contentHash(), second.contentHash());
        assertTrue(first.contentJson().contains("approved for release"));
        assertTrue(first.markdown().contains("# 审核报告"));
        assertTrue(first.markdown().contains("最终决定版本"));
        assertEquals(2, service.findVersions(reviewId).size());
        try (java.io.InputStream input = java.util.Objects.requireNonNull(
                getClass().getResourceAsStream("/golden/review-report.md"))) {
            assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8), first.markdown());
        }
    }

    @Test
    void finalGateEventTriggersBestEffortReportGeneration() {
        ReviewEvent event = ReviewEvent.committed(10L, new ReviewEventDraft(
                reviewId, 1, ReviewEventType.HUMAN_GATE_FINALIZED, ReviewStage.NOTIFYING, RoleType.DIRECTOR,
                null, null, null, null, null, 95, Instant.parse("2026-07-16T08:01:00Z"), 1, Map.of()));

        service.onCommitted(event);

        assertTrue(reportStore.findLatest(reviewId).isPresent());
    }
}
