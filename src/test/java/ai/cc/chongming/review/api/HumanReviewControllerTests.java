package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.HumanReviewService;
import ai.cc.chongming.review.domain.model.HumanReviewItem;
import ai.cc.chongming.review.domain.model.HumanReviewItem.ItemStatus;
import ai.cc.chongming.review.domain.model.HumanReviewItem.ItemType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [AIREVIEW-PLAN-011#1.2] HTTP contract tests for human review draft CRUD.
 *
 * @author wangli
 */
class HumanReviewControllerTests {

    private HumanReviewService service;
    private ReviewRegistry reviewRegistry;
    private MockMvc mockMvc;
    private UUID reviewId;
    private Review review;

    @BeforeEach
    void setUp() {
        service = mock(HumanReviewService.class);
        reviewRegistry = mock(ReviewRegistry.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new HumanReviewController(service, reviewRegistry))
                .setControllerAdvice(new HumanReviewExceptionHandler())
                .build();
        reviewId = UUID.randomUUID();
        review = Review.restore(new ReviewId(reviewId), ReviewStage.WAITING_HUMAN, 1, 0L, List.of(), Map.of());
        when(reviewRegistry.find(new ReviewId(reviewId))).thenReturn(Optional.of(review));
    }

    @Test
    void createsDraftWithReviewScopedPath() throws Exception {
        HumanReviewItem item = item(0L, ItemStatus.DRAFT);
        when(service.create(eq(review), any())).thenReturn(item);

        mockMvc.perform(post("/api/reviews/{reviewId}/human-review-items", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemId").value(item.itemId().toString()))
                .andExpect(jsonPath("$.severity").value("P1"));
    }

    @Test
    void updatesAndSoftDeletesWithExpectedVersion() throws Exception {
        HumanReviewItem updated = item(1L, ItemStatus.DRAFT);
        when(service.update(eq(review), eq(updated.itemId()), eq(0L), any())).thenReturn(updated);

        mockMvc.perform(patch("/api/reviews/{reviewId}/human-review-items/{itemId}", reviewId, updated.itemId())
                        .param("expectedVersion", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(delete("/api/reviews/{reviewId}/human-review-items/{itemId}", reviewId, updated.itemId())
                        .param("expectedVersion", "1"))
                .andExpect(status().isNoContent());
        verify(service).delete(review, updated.itemId(), 1L);
    }

    @Test
    void returnsStableErrorsForUnknownReviewAndVersionConflict() throws Exception {
        UUID unknownReviewId = UUID.randomUUID();
        when(reviewRegistry.find(new ReviewId(unknownReviewId))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/reviews/{reviewId}/human-review-items", unknownReviewId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HUMAN_REVIEW_NOT_FOUND"));

        when(service.update(eq(review), any(), eq(0L), any()))
                .thenThrow(new IllegalStateException("expectedVersion does not match human review item version"));
        mockMvc.perform(patch("/api/reviews/{reviewId}/human-review-items/{itemId}", reviewId, UUID.randomUUID())
                        .param("expectedVersion", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HUMAN_REVIEW_CONFLICT"));
    }

    private HumanReviewItem item(long version, ItemStatus status) {
        Instant now = Instant.parse("2026-07-16T08:00:00Z");
        return new HumanReviewItem(
                UUID.randomUUID(),
                new ReviewId(reviewId),
                ItemType.RISK,
                ClaimSeverity.P1,
                "Missing authorization",
                "An authorization check is required.",
                List.of(),
                List.of(),
                "Require remediation",
                version,
                status,
                "reviewer-1",
                now,
                now);
    }

    private String requestJson() {
        return """
                {"type":"RISK","severity":"P1","title":"Missing authorization",
                 "content":"An authorization check is required.","claimIds":[],"evidenceIds":[],
                 "action":"Require remediation"}
                """;
    }
}
