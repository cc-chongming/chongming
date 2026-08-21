package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.NotificationOutboxService;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationOutboxEntry;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewRepositories;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.Permission;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
    private ObjectProvider<ReviewRepositories> repositories;
    private MockMvc mockMvc;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        service = mock(NotificationOutboxService.class);
        registry = mock(ReviewRegistry.class);
        identityProvider = mock(ReviewerIdentityProvider.class);
        repositories = mock(ObjectProvider.class);
        when(repositories.getIfAvailable()).thenReturn(null);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new NotificationOutboxController(service, registry, identityProvider, repositories))
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

    @Test
    void restoresPersistedReviewAfterRestartBeforeRetry() throws Exception {
        UUID restoredId = UUID.randomUUID();
        when(registry.find(new ReviewId(restoredId))).thenReturn(Optional.empty());
        ReviewRepositories reviewRepositories = mock(ReviewRepositories.class);
        when(repositories.getIfAvailable()).thenReturn(reviewRepositories);
        Review restored = Review.restore(new ReviewId(restoredId), ReviewStage.NOTIFYING, 1, 0L, List.of(), Map.of());
        when(reviewRepositories.findReview(new ReviewId(restoredId))).thenReturn(Optional.of(restored));
        when(identityProvider.currentReviewer()).thenReturn(new ReviewerIdentity("reviewer-1", Set.of(Permission.REVIEW)));
        when(service.retryNow(any(), any(), eq(0L), eq("reviewer-1"))).thenReturn(entry(new ReviewId(restoredId)));

        mockMvc.perform(post("/api/reviews/{reviewId}/notifications/{notificationId}/retry", restoredId, UUID.randomUUID())
                        .param("expectedVersion", "0"))
                .andExpect(status().isOk());
        verify(registry).register(restored);
    }

    @Test
    void missingReviewStillReturnsNotFoundWhenPersistenceUnavailable() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(registry.find(new ReviewId(unknownId))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/reviews/{reviewId}/notifications", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    private NotificationOutboxEntry entry() {
        return entry(new ReviewId(reviewId));
    }

    private NotificationOutboxEntry entry(ReviewId entryReviewId) {
        NotificationCommand command = new NotificationCommand(
                entryReviewId, 1L, "learning-platform", "recipient-placeholder", GateResult.PASS,
                "approved", List.of(), "/api/reviews/" + entryReviewId.value() + "/report");
        Instant createdAt = Instant.parse("2026-07-16T08:00:00Z");
        NotificationOutboxEntry sending = NotificationOutboxEntry.pending(command, "a".repeat(64), createdAt)
                .claim(0L, createdAt);
        return sending.markFailed(1L, "MCP_DISABLED", false, createdAt, createdAt);
    }
}
