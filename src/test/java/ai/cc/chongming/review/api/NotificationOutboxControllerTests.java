package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.NotificationOutboxService;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationOutboxEntry;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.Permission;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [AIREVIEW-PLAN-011#1.5,#1.7] HTTP contract tests for notification state visibility and authorized retry.
 *
 * @author wangli
 */
class NotificationOutboxControllerTests {

    private NotificationOutboxService service;
    private ReviewRegistry registry;
    private ReviewerIdentityProvider identityProvider;
    private MockMvc mockMvc;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        service = mock(NotificationOutboxService.class);
        registry = mock(ReviewRegistry.class);
        identityProvider = mock(ReviewerIdentityProvider.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationOutboxController(service, registry, identityProvider))
                .setControllerAdvice(new NotificationOutboxExceptionHandler())
                .build();
        reviewId = UUID.randomUUID();
        when(registry.find(new ReviewId(reviewId))).thenReturn(Optional.of(
                Review.restore(new ReviewId(reviewId), ReviewStage.NOTIFYING, 1, 0L, List.of(), Map.of())));
    }

    @Test
    void listsAndRetriesWithoutChangingNotificationIdentity() throws Exception {
        NotificationOutboxEntry failed = entry();
        NotificationOutboxEntry retried = failed.retryNow(2L, Instant.parse("2026-07-16T08:01:00Z"));
        when(service.findByReview(new ReviewId(reviewId))).thenReturn(List.of(failed));
        when(identityProvider.currentReviewer()).thenReturn(new ReviewerIdentity("reviewer-1", Set.of(Permission.REVIEW)));
        when(service.retryNow(eq(new ReviewId(reviewId)), eq(failed.notificationId()), eq(2L), eq("reviewer-1")))
                .thenReturn(retried);

        mockMvc.perform(get("/api/reviews/{reviewId}/notifications", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].command.gateVersion").value(1));
        mockMvc.perform(post("/api/reviews/{reviewId}/notifications/{notificationId}/retry", reviewId, failed.notificationId())
                        .param("expectedVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(failed.notificationId().toString()));
        verify(service).retryNow(new ReviewId(reviewId), failed.notificationId(), 2L, "reviewer-1");
    }

    @Test
    void rejectsRetryWithoutReviewerPermission() throws Exception {
        when(identityProvider.currentReviewer()).thenReturn(new ReviewerIdentity("anonymous", Set.of()));

        mockMvc.perform(post("/api/reviews/{reviewId}/notifications/{notificationId}/retry", reviewId, UUID.randomUUID())
                        .param("expectedVersion", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_FORBIDDEN"));
    }

    private NotificationOutboxEntry entry() {
        NotificationCommand command = new NotificationCommand(
                new ReviewId(reviewId), 1L, "learning-platform", "recipient-placeholder", GateResult.PASS,
                "approved", List.of(), "/api/reviews/" + reviewId + "/report");
        Instant createdAt = Instant.parse("2026-07-16T08:00:00Z");
        NotificationOutboxEntry sending = NotificationOutboxEntry.pending(command, "a".repeat(64), createdAt)
                .claim(0L, createdAt);
        return sending.markFailed(1L, "MCP_DISABLED", false, createdAt, createdAt);
    }
}
