package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewStartReservationStore;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import ai.cc.chongming.review.infrastructure.persistence.repository.MyBatisReviewStartReservationStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
    void reservesThePersistedPendingRootBeforeChangingTheInMemoryAggregate() {
        ReviewStartReservationStore reservationStore = mock(ReviewStartReservationStore.class);
        when(reservationStore.claimStartFromPending(reviewId, 0L, 1, 3L)).thenReturn(false);
        commandService = new ReviewCommandService(
                reviewRegistry,
                new ReviewLifecycleService(new InMemoryReviewDebateStore(), stateMachine, eventService),
                orchestrationService,
                stateMachine,
                eventService,
                new ai.cc.chongming.review.config.ReviewDiagnosticsProperties(false),
                null,
                null,
                reservationStore);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> commandService.start(reviewId, startCommand(0L, "start-cas-001")))
                .isInstanceOf(ai.cc.chongming.review.domain.exception.ReviewDomainException.class)
                .hasMessageContaining("current persisted PENDING");

        assertThat(review.stage()).isEqualTo(ReviewStage.PENDING);
        assertThat(review.version()).isZero();
        verify(orchestrationService, never()).start(any());
    }

    @Test
    void writesThePlanningCasWithTheVersionProducedByTheStartCommand() {
        ReviewStartReservationStore reservationStore = mock(ReviewStartReservationStore.class);
        when(reservationStore.claimStartFromPending(reviewId, 0L, 1, 3L)).thenReturn(true);
        when(orchestrationService.start(any())).thenReturn(Mono.never());
        commandService = new ReviewCommandService(
                reviewRegistry,
                new ReviewLifecycleService(new InMemoryReviewDebateStore(), stateMachine, eventService),
                orchestrationService,
                stateMachine,
                eventService,
                new ai.cc.chongming.review.config.ReviewDiagnosticsProperties(false),
                null,
                null,
                reservationStore);

        commandService.start(reviewId, startCommand(0L, "start-cas-002"));

        verify(reservationStore).claimStartFromPending(reviewId, 0L, 1, 3L);
    }

    @Test
    void springWiringInjectsThePersistentStartReservationInsteadOfTheNoop() {
        ReviewPersistenceMapper mapper = mock(ReviewPersistenceMapper.class);
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        when(mapper.claimStartFromPending(reviewId.value().toString(), 0L, 1, 3L)).thenReturn(1);
        when(orchestrationService.start(any())).thenReturn(Mono.never());
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(requirementSnapshot());
        when(snapshotService.findExistingSnapshot(reviewId, 1, "approved-repository"))
                .thenReturn(Optional.of(repositorySnapshot()));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ai.cc.chongming.review.domain.repository.ReviewRegistry.class, () -> reviewRegistry);
            context.registerBean(ReviewLifecycleService.class,
                    () -> new ReviewLifecycleService(new InMemoryReviewDebateStore(), stateMachine, eventService));
            context.registerBean(ReviewOrchestrationService.class, () -> orchestrationService);
            context.registerBean(ReviewStateMachine.class, () -> stateMachine);
            context.registerBean(ReviewEventPublisher.class, () -> eventService);
            context.registerBean(ai.cc.chongming.review.config.ReviewDiagnosticsProperties.class,
                    () -> new ai.cc.chongming.review.config.ReviewDiagnosticsProperties(false));
            context.registerBean(ReviewIntakeService.class, () -> intakeService);
            context.registerBean(RepositorySnapshotService.class, () -> snapshotService);
            context.registerBean(ReviewStartReservationStore.class,
                    () -> new MyBatisReviewStartReservationStore(mapper));
            context.registerBean(ReviewCommandService.class);
            context.refresh();

            context.getBean(ReviewCommandService.class).start(reviewId, startCommand(0L, "start-wiring-001"));
        }

        verify(mapper).claimStartFromPending(reviewId.value().toString(), 0L, 1, 3L);
    }

    @Test
    void bindsTheRepositorySnapshotBeforeStartingTheHarnessWorkflow() {
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        RequirementSnapshot requirement = new RequirementSnapshot(
                UUID.randomUUID(), reviewId, 1, "user-001", "approved-repository", null, null,
                "requirement.md", "a".repeat(64), "b".repeat(64), "test",
                new RequirementSnapshot.RequirementDocument(List.of(), List.of(), 0, 0, false), java.time.Instant.now());
        RepositorySnapshot repositorySnapshot = new RepositorySnapshot(
                UUID.randomUUID(), reviewId, "approved-repository", java.nio.file.Path.of("build/source"),
                java.nio.file.Path.of("build/snapshot"), "c".repeat(40), "main", false,
                "d".repeat(64), 1, java.time.Instant.now());
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(requirement);
        when(snapshotService.findExistingSnapshot(reviewId, 1, "approved-repository")).thenReturn(Optional.empty());
        when(snapshotService.bindSnapshot(reviewId, 1, "approved-repository", requirement.contentHash(),
                IntakeCancellation.neverCancelled())).thenReturn(repositorySnapshot);
        when(orchestrationService.start(any())).thenReturn(Mono.never());
        commandService = new ReviewCommandService(
                reviewRegistry,
                new ReviewLifecycleService(new InMemoryReviewDebateStore(), stateMachine, eventService),
                orchestrationService,
                stateMachine,
                eventService,
                new ai.cc.chongming.review.config.ReviewDiagnosticsProperties(false),
                intakeService,
                snapshotService);

        commandService.start(reviewId, startCommand(0L, "start-bind-001"));

        var order = inOrder(snapshotService, orchestrationService);
        order.verify(snapshotService, timeout(1_000)).bindSnapshot(
                reviewId, 1, "approved-repository", requirement.contentHash(), IntakeCancellation.neverCancelled());
        order.verify(orchestrationService, timeout(1_000)).start(any());
    }

    @Test
    void startReusesThePreviewSnapshotWithoutRebindingIt() {
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(requirementSnapshot());
        when(snapshotService.findExistingSnapshot(reviewId, 1, "approved-repository"))
                .thenReturn(Optional.of(repositorySnapshot()));
        when(orchestrationService.start(any())).thenReturn(Mono.never());
        commandService = commandService(intakeService, snapshotService);

        commandService.start(reviewId, startCommand(0L, "start-reuse-preview-001"));

        verify(orchestrationService, timeout(1_000)).start(any());
        verify(snapshotService, never()).bindSnapshot(
                any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void scoutPreviewReusesTheAttemptSnapshotWithoutRebindingIt() {
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        RepositorySnapshot snapshot = repositorySnapshot();
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(requirementSnapshot());
        when(snapshotService.findExistingSnapshot(reviewId, 1, "approved-repository"))
                .thenReturn(Optional.of(snapshot));
        commandService = commandService(intakeService, snapshotService);

        RepositorySnapshot result = commandService.prepareSnapshotForScoutPreview(reviewId, 1);

        assertThat(result).isSameAs(snapshot);
        verify(snapshotService, never()).bindSnapshot(
                any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void scoutPreviewDoesNotInitializeAChangedAttemptAfterPlanningHasStarted() {
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        RepositorySnapshotService snapshotService = mock(RepositorySnapshotService.class);
        when(intakeService.requireSnapshot(reviewId, 1)).thenReturn(requirementSnapshot());
        when(snapshotService.findExistingSnapshot(reviewId, 1, "approved-repository"))
                .thenReturn(Optional.empty());
        commandService = commandService(intakeService, snapshotService);
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> commandService.prepareSnapshotForScoutPreview(reviewId, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only initialize a snapshot while the attempt is PENDING");

        verify(snapshotService, never()).bindSnapshot(
                any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void startPublishesFailureTypeAndSafeMessageWhenStartupFails() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        when(orchestrationService.start(any())).thenReturn(Mono.<ReviewOrchestrationService.StartResult>error(
                new IllegalArgumentException("password=secret role profile is missing"))
                .doFinally(signal -> completed.countDown()));
        when(orchestrationService.releaseRuntime(reviewId, 1)).thenReturn(Mono.empty());

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
        verify(orchestrationService, timeout(1_000)).releaseRuntime(reviewId, 1);
    }

    @Test
    void startupCancellationDoesNotTurnTheReviewIntoFailure() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        when(orchestrationService.start(any())).thenReturn(Mono.<ReviewOrchestrationService.StartResult>error(
                new CancellationException("runtime cancellation requested"))
                .doFinally(signal -> completed.countDown()));
        when(orchestrationService.releaseRuntime(reviewId, 1)).thenReturn(Mono.empty());
        when(orchestrationService.requestRuntimeCancellation(reviewId, 1)).thenReturn(Mono.empty());

        commandService.start(reviewId, startCommand(0L, "start-cancelled-001"));

        assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(review.stage()).isEqualTo(ReviewStage.PLANNING);
        assertThat(eventService.replay(reviewId, 0L, 10))
                .noneMatch(event -> event.type() == ReviewEventType.REVIEW_FAILED);

        commandService.cancel(reviewId, review.version()).block();

        assertThat(review.stage()).isEqualTo(ReviewStage.CANCELLED);
        verify(orchestrationService, timeout(1_000)).releaseRuntime(reviewId, 1);
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

    @Test
    void retryCopiesTheAcceptedInputForTheFreshAttempt() {
        ReviewIntakeService intakeService = mock(ReviewIntakeService.class);
        commandService = commandService(intakeService, mock(RepositorySnapshotService.class));
        when(orchestrationService.requestRuntimeCancellation(reviewId, 1)).thenReturn(Mono.empty());
        commandService.cancel(reviewId, 0L).block();

        commandService.retry(reviewId, review.version());

        verify(intakeService).copySnapshotForRetry(reviewId, 1, 2);
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

    private ReviewCommandService commandService(
            ReviewIntakeService intakeService, RepositorySnapshotService snapshotService) {
        return new ReviewCommandService(
                reviewRegistry,
                new ReviewLifecycleService(new InMemoryReviewDebateStore(), stateMachine, eventService),
                orchestrationService,
                stateMachine,
                eventService,
                new ai.cc.chongming.review.config.ReviewDiagnosticsProperties(false),
                intakeService,
                snapshotService);
    }

    private RequirementSnapshot requirementSnapshot() {
        return new RequirementSnapshot(
                UUID.randomUUID(), reviewId, 1, "user-001", "approved-repository", null, null,
                "requirement.md", "a".repeat(64), "b".repeat(64), "test",
                new RequirementSnapshot.RequirementDocument(List.of(), List.of(), 0, 0, false), java.time.Instant.now());
    }

    private RepositorySnapshot repositorySnapshot() {
        return new RepositorySnapshot(
                UUID.randomUUID(), reviewId, "approved-repository", java.nio.file.Path.of("build/source"),
                java.nio.file.Path.of("build/snapshot"), "c".repeat(40), "main", false,
                "d".repeat(64), 1, java.time.Instant.now());
    }
}
