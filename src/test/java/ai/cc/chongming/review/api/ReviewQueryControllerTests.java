package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewQueryService;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [AIREVIEW-PLAN-010#1.3] HTTP contract tests for public review read models.
 *
 * @author wangli
 */
class ReviewQueryControllerTests {

    private ReviewQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(ReviewQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReviewQueryController(queryService))
                .setControllerAdvice(new ReviewQueryExceptionHandler())
                .build();
    }

    @Test
    void returnsEventBackedSummaryWithFormattedTime() throws Exception {
        UUID reviewId = UUID.randomUUID();
        when(queryService.findSummary(new ReviewId(reviewId))).thenReturn(Optional.of(new ReviewQueryService.ReviewSummary(
                reviewId,
                2,
                "JUDGING",
                80,
                16L,
                4L,
                "2026-07-16 14:00:00",
                new ReviewQueryService.GateView("CONDITIONAL", "DRAFT", "AI", "needs review", "2026-07-16 14:00:00"),
                new ReviewQueryService.ContextScoutView(
                        "DEGRADED",
                        "MODEL_CALL_TIMEOUT",
                        "Context Scout 模型调用超时，Director 将继续评审。",
                        "2026-07-16 13:59:00"))));

        mockMvc.perform(get("/api/reviews/{reviewId}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.stage").value("JUDGING"))
                .andExpect(jsonPath("$.reviewVersion").value(4))
                .andExpect(jsonPath("$.occurredAt").value("2026-07-16 14:00:00"))
                .andExpect(jsonPath("$.gate.result").value("CONDITIONAL"))
                .andExpect(jsonPath("$.contextScout.status").value("DEGRADED"))
                .andExpect(jsonPath("$.contextScout.reasonCode").value("MODEL_CALL_TIMEOUT"));
    }

    @Test
    void returnsSequenceCursorForPlanEvents() throws Exception {
        UUID reviewId = UUID.randomUUID();
        when(queryService.findPlans(new ReviewId(reviewId), 7L, 20)).thenReturn(new ReviewQueryService.EventPage(
                List.of(new ReviewQueryService.EventView(
                        UUID.randomUUID(), 8L, reviewId, 1, "PLAN_CREATED", "PLAN", "PLANNING",
                        null, null, null, null, null, null, 20, "2026-07-16 14:00:00", 1, Map.of("plan", "v1"))),
                8L));

        mockMvc.perform(get("/api/reviews/{reviewId}/plans", reviewId)
                        .param("afterSequence", "7")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sequence").value(8))
                .andExpect(jsonPath("$.items[0].payload.plan").value("v1"))
                .andExpect(jsonPath("$.nextAfterSequence").value(8));
    }

    @Test
    void readsEvidenceByServerControlledIdOnly() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        when(queryService.findEvidence(new ReviewId(reviewId), new EvidenceId(evidenceId))).thenReturn(Optional.of(
                new ReviewQueryService.EvidenceView(
                        evidenceId,
                        UUID.randomUUID(),
                        "abc123",
                        "src/main/java/App.java",
                        8,
                        "return value;",
                        "a".repeat(64),
                        "b".repeat(64),
                        "2026-07-16 14:00:00")));

        mockMvc.perform(get("/api/reviews/{reviewId}/evidence/{evidenceId}", reviewId, evidenceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotRelativePath").value("src/main/java/App.java"))
                .andExpect(jsonPath("$.sourceAbsolutePath").doesNotExist());
    }

    @Test
    void returnsNotFoundForUnknownEvidence() throws Exception {
        when(queryService.findEvidence(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/reviews/{reviewId}/evidence/{evidenceId}", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
