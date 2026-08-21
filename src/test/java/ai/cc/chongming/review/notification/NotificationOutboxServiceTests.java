package ai.cc.chongming.review.notification;

import ai.cc.chongming.auth.domain.User;
import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.application.NotificationOutboxService;
import ai.cc.chongming.review.application.ReviewEventService;
import ai.cc.chongming.review.application.ReviewOrchestrationService;
import ai.cc.chongming.review.config.NotificationOutboxProperties;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventCategory;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import ai.cc.chongming.review.domain.model.NotificationOutboxEntry;
import ai.cc.chongming.review.domain.model.NotificationOutboxEntry.DeliveryStatus;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import ai.cc.chongming.review.infrastructure.human.InMemoryHumanGateDecisionStore;
import ai.cc.chongming.review.infrastructure.notification.InMemoryNotificationOutboxStore;
import ai.cc.chongming.review.infrastructure.notification.NotificationDeliveryRouter;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Mono;

/**
 * [AIREVIEW-PLAN-011#1.5] Verifies idempotent queueing, retry and terminal failure without rolling back Gate state.
 *
 * @author wangli
 */
class NotificationOutboxServiceTests {

    private final Instant now = Instant.parse("2026-07-16T08:00:00Z");
    private final ReviewId reviewId = new ReviewId(UUID.randomUUID());
    private final InMemoryNotificationOutboxStore outboxStore = new InMemoryNotificationOutboxStore();
    private final InMemoryHumanGateDecisionStore decisionStore = new InMemoryHumanGateDecisionStore();
    private final InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
    private final ReviewEventService events = new ReviewEventService(new InMemoryReviewEventStore());
    private NotificationOutboxService service;
    private Review review;
    private HumanGateDecision decision;
    private ReviewOrchestrationService orchestrationService;

    @BeforeEach
    void setUp() {
        review = Review.restore(reviewId, ReviewStage.NOTIFYING, 1, 5L, List.of(), Map.of());
        registry.register(review);
        decision = new HumanGateDecision(reviewId, 1L, GateResult.CONDITIONAL, "remediate before release",
                List.of("add authorization"), null, "reviewer-1", null, now);
        decisionStore.append(decision);
        orchestrationService = mock(ReviewOrchestrationService.class);
        when(orchestrationService.releaseRuntime(reviewId, 1)).thenReturn(Mono.empty());
        service = new NotificationOutboxService(
                outboxStore,
                decisionStore,
                registry,
                new ReviewStateMachine(),
                events,
                new NotificationOutboxProperties(false, false, "learning-platform", "recipient-placeholder",
                        "MISSING_TEST_TOKEN", 3, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                Clock.fixed(now, ZoneOffset.UTC),
                orchestrationService);
    }

    @Test
    void queuesOnceAndCompletesReviewAfterSuccessfulDelivery() {
        NotificationOutboxEntry first = service.enqueue(review, decision);
        NotificationOutboxEntry second = service.enqueue(review, decision);

        assertSame(first, second);
        assertEquals(1, service.dispatchDue(command -> new NotificationDeliveryReceipt("202", "a".repeat(64)), 10));

        NotificationOutboxEntry delivered = service.findByReview(reviewId).getFirst();
        assertEquals(DeliveryStatus.SENT, delivered.deliveryStatus());
        assertEquals(1, delivered.attemptCount());
        assertEquals(ReviewStage.COMPLETED, review.stage());
        verify(orchestrationService).releaseRuntime(reviewId, 1);
        assertTrue(events.replay(reviewId, 0L, 10).stream().anyMatch(event -> event.type() == ReviewEventType.NOTIFICATION_SENT));
    }

    @Test
    void retriesWithSameIdempotencyKeyAndEventuallySends() {
        NotificationOutboxEntry queued = service.enqueue(review, decision);

        service.dispatchDue(command -> {
            throw new NotificationDeliveryException("TEMPORARY", true, "temporary outage");
        }, 10);
        NotificationOutboxEntry failed = service.findByReview(reviewId).getFirst();
        assertEquals(DeliveryStatus.FAILED, failed.deliveryStatus());
        assertEquals(1, failed.attemptCount());

        NotificationOutboxEntry retry = service.retryNow(reviewId, queued.notificationId(), failed.version());
        service.dispatchDue(command -> new NotificationDeliveryReceipt("200", "b".repeat(64)), 10);
        NotificationOutboxEntry delivered = service.findByReview(reviewId).getFirst();
        assertEquals(queued.notificationId(), retry.notificationId());
        assertEquals(queued.command().idempotencyKey(), delivered.command().idempotencyKey());
        assertEquals(DeliveryStatus.SENT, delivered.deliveryStatus());
        assertEquals(2, delivered.attemptCount());
    }

    @Test
    void finalGateMailNotificationUsesReviewerEmailAsDestination() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername("reviewer-1")).thenReturn(Optional.of(
                new User(2L, "reviewer-1", "hash", "Reviewer", "ADMIN", null, "reviewer@qq.com")));
        NotificationOutboxService mailService = new NotificationOutboxService(
                outboxStore,
                decisionStore,
                registry,
                new ReviewStateMachine(),
                events,
                new NotificationOutboxProperties(false, false, "smtp-mail", "fallback@example.com",
                        "MISSING_TEST_TOKEN", 3, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                Clock.fixed(now, ZoneOffset.UTC),
                orchestrationService,
                userRepository,
                null,
                null);

        NotificationOutboxEntry queued = mailService.enqueue(review, decision);

        assertEquals("smtp-mail", queued.command().channel());
        assertEquals("reviewer@qq.com", queued.command().destination());
    }

    @Test
    void retryAfterRestartRecoversFinalGateEntryFromPersistedDecision() {
        NotificationOutboxEntry recovered = service.retryNow(reviewId, UUID.randomUUID(), 0L, "reviewer-1");

        assertEquals(DeliveryStatus.PENDING, recovered.deliveryStatus());
        assertEquals(1, service.findByReview(reviewId).size());
        assertTrue(events.replay(reviewId, 0L, 10).stream()
                .anyMatch(event -> event.type() == ReviewEventType.NOTIFICATION_RETRY_REQUESTED));
    }

    @Test
    void matrixTransitionNotificationIsEnqueuedWithoutGateResult() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername("dev")).thenReturn(Optional.of(
                new User(1L, "dev", "hash", "Dev", "DEV", null, "dev@qq.com")));
        when(userRepository.findAll()).thenReturn(List.of());
        NotificationOutboxService matrixService = new NotificationOutboxService(
                outboxStore,
                decisionStore,
                registry,
                new ReviewStateMachine(),
                events,
                new NotificationOutboxProperties(false, false, "learning-platform", "recipient-placeholder",
                        "MISSING_TEST_TOKEN", 3, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                Clock.fixed(now, ZoneOffset.UTC),
                orchestrationService,
                userRepository,
                null,
                null);

        ReviewEvent event = new ReviewEvent(UUID.randomUUID(), 7L, reviewId, 1,
                ReviewEventType.TASK_HANDOFF, ReviewEventCategory.TASK, ReviewStage.NOTIFYING,
                RoleType.DIRECTOR, null, null, null, null, null, 90, now, 1, Map.of("to", "dev"));
        matrixService.onCommitted(event);

        List<NotificationOutboxEntry> entries = service.findByReview(reviewId);
        assertEquals(1, entries.size());
        NotificationOutboxEntry queued = entries.getFirst();
        assertEquals(NotificationDeliveryRouter.MAIL_CHANNEL, queued.command().channel());
        assertEquals("dev@qq.com", queued.command().destination());
        assertEquals(DeliveryStatus.PENDING, queued.deliveryStatus());
    }

    @Test
    void nonRetryableFailureBecomesDeadWithoutChangingFinalGate() {
        service.enqueue(review, decision);

        service.dispatchDue(command -> {
            throw new NotificationDeliveryException("MCP_DISABLED", false, "disabled");
        }, 10);

        NotificationOutboxEntry dead = service.findByReview(reviewId).getFirst();
        assertEquals(DeliveryStatus.DEAD, dead.deliveryStatus());
        assertEquals(ReviewStage.NOTIFYING, review.stage());
        assertEquals(List.of(decision), decisionStore.findVersions(reviewId));
        assertTrue(events.replay(reviewId, 0L, 10).stream().anyMatch(event -> event.type() == ReviewEventType.NOTIFICATION_DEAD));
    }
}
