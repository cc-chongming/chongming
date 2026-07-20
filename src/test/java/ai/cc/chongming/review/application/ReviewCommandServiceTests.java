package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * [AIREVIEW-PLAN-010#1.6,#1.7] Verifies HTTP-command application semantics without an MVC or AgentScope runtime.
 *
 * @author wangli
 */
class ReviewCommandServiceTests {

    private final ReviewId reviewId = new ReviewId(UUID.randomUUID());
    private final InMemoryReviewRegistry reviewRegistry = new InMemoryReviewRegistry();
    private final Review review = Review.pending(reviewId);
    private final ReviewStateMachine stateMachine = new ReviewStateMachine();
    private final InMemoryReviewEventStore eventStore = new InMemoryReviewEventStore();
    private final ReviewEventService eventService = new ReviewEventService(eventStore);
    private final ReviewOrchestrationService orchestrationService = mock(ReviewOrchestrationService.class);
    private ReviewCommandService commandService;

    @BeforeEach
    void setUp() {
        reviewRegistry.register(review);
        commandService = new ReviewCommandService(
                reviewRegistry,
                new ReviewLifecycleService(new InMemoryReviewDebateStore(), stateMachine, eventService),
                orchestrationService,
                stateMachine,
                eventService);
    }

    @Test
    void startRecordsAnIdempotentCommandAndReturnsBeforeTheRuntimeCompletes() {
        when(orchestrationService.start(any())).thenReturn(Mono.never());

        ReviewCommandService.StartReviewResult accepted = commandService.start(reviewId, startCommand(0L, "start-001"));
        ReviewCommandService.StartReviewResult replayed = commandService.start(reviewId, startCommand(0L, "start-001"));

        assertThat(accepted.stage()).isEqualTo(ReviewStage.PLANNING.name());
        assertThat(accepted.version()).isEqualTo(3L);
        assertThat(accepted.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(review.stage()).isEqualTo(ReviewStage.PLANNING);
        verify(orchestrationService, timeout(1_000)).start(any());
    }

    @Test
    void startPublishesFailureTypeAndSafeMessageWhenStartupFails() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        when(orchestrationService.start(any())).thenReturn(Mono.<ReviewOrchestrationService.StartResult>error(
                new IllegalArgumentException("password=secret role profile is missing"))
                .doFinally(signal -> completed.countDown()));

        commandService.start(reviewId, startCommand(0L, "start-failure-001"));

        verify(orchestrationService, timeout(1_000)).start(any());
        assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(review.stage()).isEqualTo(ReviewStage.FAILED);
        assertThat(eventService.replay(reviewId, 0L, 10))
                .anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo(ReviewEventType.REVIEW_FAILED);
                    assertThat(event.payload())
                            .containsEntry("failureType", "IllegalArgumentException")
                            .doesNotContainKey("failureMessage")
                            .doesNotContainValue("password=secret");
                });
    }

    @Test
    void redactsCredentialsFromLocalDiagnosticText() {
        String diagnostic = "password=secret Authorization: Bearer bearer-secret "
                + "https://alice:pw@example.test/path?token=query-secret sk-private-key";

        String redacted = ReviewCommandService.redactDiagnosticText(diagnostic, 1_000);

        assertThat(redacted)
                .doesNotContain("secret", "bearer-secret", "alice:pw", "query-secret", "sk-private-key")
                .contains("[REDACTED]");
    }

    @Test
    void cancelRequestsTheRuntimeBeforePublishingOneTerminalEvent() {
        when(orchestrationService.requestRuntimeCancellation(reviewId, 1)).thenReturn(Mono.empty());

        ReviewCommandService.CancelReviewResult result = commandService.cancel(reviewId, 0L).block();

        assertThat(result.replayed()).isFalse();
        assertThat(review.stage()).isEqualTo(ReviewStage.CANCELLED);
        verify(orchestrationService).requestRuntimeCancellation(reviewId, 1);
        assertThat(eventService.replay(reviewId, 0L, 10)).extracting(event -> event.type())
                .containsExactly(ReviewEventType.REVIEW_CANCELLED);
    }

    @Test
    void retryCreatesPendingAttemptThatCanBeStartedSeparately() {
        when(orchestrationService.requestRuntimeCancellation(reviewId, 1)).thenReturn(Mono.empty());
        commandService.cancel(reviewId, 0L).block();

        ReviewCommandService.RetryReviewResult result = commandService.retry(reviewId, review.version());

        assertThat(result.previousAttempt()).isEqualTo(1);
        assertThat(result.attemptNo()).isEqualTo(2);
        assertThat(review.stage()).isEqualTo(ReviewStage.PENDING);
        assertThat(eventService.replay(reviewId, 0L, 10)).extracting(event -> event.type())
                .containsExactly(ReviewEventType.REVIEW_CANCELLED, ReviewEventType.REVIEW_RETRIED);
    }

    private ReviewCommandService.StartReviewCommand startCommand(long expectedVersion, String idempotencyKey) {
        return new ReviewCommandService.StartReviewCommand(
                expectedVersion,
                idempotencyKey,
                "user-001",
                "trace-001",
                List.of("Review the acceptance criteria"),
                "Initial total plan",
                "Begin review");
    }
}
