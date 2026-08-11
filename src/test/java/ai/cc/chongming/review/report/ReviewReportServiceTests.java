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
        when(queryService.findClaims(reviewId)).thenReturn(List.of(new ReviewQueryService.ClaimView(
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                "PRODUCT", "incremental-sync-core", "P1", "SUPPORT",
                "增量同步必须上线。", "全量方案已到瓶颈。", "SUBMITTED",
                List.of(UUID.fromString("50000000-0000-0000-0000-000000000001")))));
        // [AIREVIEW-PLAN-024#方案5] five-status assessment facts are store projections; entries are
        // intentionally unsorted here so the report's stable role+checkpointKey ordering is verified.
        when(queryService.findAssessmentViews(reviewId, 1)).thenReturn(List.of(
                assessmentView("FRONTEND", "incremental_render", "CONFIRMED", "前端增量渲染无问题。", null,
                        List.of(UUID.fromString("60000000-0000-0000-0000-000000000001"))),
                assessmentView("BACKEND", "token_expiry_policy", "CONFIRMED", "令牌过期策略已实现。", null, List.of()),
                assessmentView("BACKEND", "audit_log_coverage", "GAP", "审计日志存在缺口。", "敏感操作未覆盖。", List.of()),
                assessmentView("PRODUCT", "requirement_traceability", "PARTIAL", "追踪部分缺失。", "两条需求无追踪号。", List.of()),
                assessmentView("FRONTEND", "snapshot_grant_scope", "UNKNOWN", "无法确认快照授权范围。",
                        "当前评审快照未授予前端文件。", List.of()),
                assessmentView("PROJECT", "milestone_plan", "NOT_APPLICABLE", "里程碑计划不适用。", null, List.of())));
        when(queryService.findAssessmentCoverage(reviewId, 1)).thenReturn(new ReviewQueryService.AssessmentCoverageView(
                24, 20, 2, 1, 1, 1, 1, List.of("SECURITY:secret_rotation")));
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
        assertTrue(first.contentJson().contains("incremental-sync-core"));
        assertTrue(first.markdown().contains("# 审核报告"));
        assertTrue(first.markdown().contains("公开论点"));
        assertTrue(first.markdown().contains("最终决定版本"));
        assertEquals(2, service.findVersions(reviewId).size());
        try (java.io.InputStream input = java.util.Objects.requireNonNull(
                getClass().getResourceAsStream("/golden/review-report.md"))) {
            assertEquals(normalizeNewlines(new String(input.readAllBytes(), StandardCharsets.UTF_8)),
                    normalizeNewlines(first.markdown()));
        }
    }

    @Test
    void rendersFiveAssessmentSectionsWithStoreDerivedCounters() throws java.io.IOException {
        ReviewReport report = service.generate(review);

        ReviewReportService.ReportContent content = new ObjectMapper()
                .readValue(report.contentJson(), ReviewReportService.ReportContent.class);
        ReviewReportService.AssessmentReportView sections = content.assessments();
        assertEquals(24, sections.required());
        assertEquals(20, sections.covered());
        assertEquals(2, sections.confirmed());
        assertEquals(1, sections.partial());
        assertEquals(1, sections.gap());
        assertEquals(1, sections.unknown());
        assertEquals(1, sections.notApplicable());
        assertEquals(List.of("SECURITY:secret_rotation"), sections.uncoveredCheckpoints());
        assertEquals(List.of("BACKEND:token_expiry_policy", "FRONTEND:incremental_render"),
                sections.confirmedEntries().stream()
                        .map(entry -> entry.role() + ":" + entry.checkpointKey()).toList());
        assertEquals("审计日志存在缺口。", sections.gapEntries().get(0).summary());
        assertEquals("无法确认快照授权范围。", sections.unknownEntries().get(0).summary());
        assertTrue(report.markdown().contains("## 检查点结论"));
        assertTrue(report.markdown().contains(
                "required=24, confirmed=2, partial=1, gap=1, unknown=1, notApplicable=1"));
        assertTrue(report.markdown().contains("未覆盖 required 检查点: SECURITY:secret_rotation"));
        assertTrue(report.markdown().contains("确定结论（CONFIRMED：2）"));
        assertTrue(report.markdown().contains("部分满足（PARTIAL：1）"));
        assertTrue(report.markdown().contains("风险缺口（GAP：1）"));
        assertTrue(report.markdown().contains("证据不足（UNKNOWN：1）"));
        assertTrue(report.markdown().contains("不适用（NOT_APPLICABLE：1）"));
    }

    @Test
    void finalGateEventTriggersBestEffortReportGeneration() {
        ReviewEvent event = ReviewEvent.committed(10L, new ReviewEventDraft(
                reviewId, 1, ReviewEventType.HUMAN_GATE_FINALIZED, ReviewStage.NOTIFYING, RoleType.DIRECTOR,
                null, null, null, null, null, 95, Instant.parse("2026-07-16T08:01:00Z"), 1, Map.of()));

        service.onCommitted(event);

        assertTrue(reportStore.findLatest(reviewId).isPresent());
    }

    private ReviewQueryService.AssessmentView assessmentView(
            String role, String checkpointKey, String status, String summary,
            String reasonSummary, List<UUID> evidenceIds) {
        return new ReviewQueryService.AssessmentView(
                role, checkpointKey, status, summary, reasonSummary, evidenceIds, "2026-07-16 15:00:00");
    }

    private String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n");
    }
}
