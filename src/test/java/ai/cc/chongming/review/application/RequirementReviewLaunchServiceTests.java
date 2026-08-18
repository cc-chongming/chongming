package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.RequirementDocument;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.protocol.RequirementLifecycleStateMachine;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore.Reservation;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.document.StoredRequirementSnapshot;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementReviewLaunchCommandStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/**
 * [AIREVIEW-PLAN-023#3] Covers intake, binding, start, replay and recoverable launch failures.
 *
 * @author zyj
 */
class RequirementReviewLaunchServiceTests {

    private final RequirementId requirementId = new RequirementId(UUID.randomUUID());
    private final ReviewId reviewId = new ReviewId(UUID.randomUUID());
    private ReviewIntakeService intakeService;
    private RequirementCommandService requirementCommandService;
    private ReviewCommandService reviewCommandService;
    private RequirementRepository requirementRepository;
    private ReviewRegistry reviewRegistry;
    private InMemoryRequirementReviewLaunchCommandStore launchCommandStore;
    private RequirementReviewLaunchService service;

    @BeforeEach
    void setUp() {
        intakeService = mock(ReviewIntakeService.class);
        requirementCommandService = mock(RequirementCommandService.class);
        reviewCommandService = mock(ReviewCommandService.class);
        requirementRepository = mock(RequirementRepository.class);
        reviewRegistry = mock(ReviewRegistry.class);
        launchCommandStore = new InMemoryRequirementReviewLaunchCommandStore();
        service = new RequirementReviewLaunchService(
                intakeService,
                requirementCommandService,
                reviewCommandService,
                requirementRepository,
                reviewRegistry,
                launchCommandStore);
    }

    @Test
    void intakesBindsAndStartsOneDraftReview() {
        Requirement draft = draft();
        Requirement submitted = submitted(reviewId);
        Review pendingReview = Review.pending(reviewId);
        when(intakeService.intake(any())).thenReturn(intakeResult(reviewId, false));
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft));
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenReturn(submitted);
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(pendingReview));
        when(reviewCommandService.start(any(), any())).thenReturn(
                new ReviewCommandService.StartReviewResult(reviewId.value(), 1, 3L, "PLANNING", false));

        RequirementReviewLaunchService.LaunchResult result = service.launch(requirementId, command(0L));

        assertThat(result.reviewId()).isEqualTo(reviewId.value());
        assertThat(result.requirementStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(result.stage()).isEqualTo("PLANNING");
        assertThat(result.phase()).isEqualTo("STARTED");
        assertThat(result.recoverable()).isFalse();
        assertThat(result.liveUrl()).isEqualTo("/reviews/" + reviewId.value() + "/live");
        ArgumentCaptor<ReviewIntakeRequest> intakeRequest = ArgumentCaptor.forClass(ReviewIntakeRequest.class);
        verify(intakeService).intake(intakeRequest.capture());
        assertThat(intakeRequest.getValue().idempotencyScope())
                .isEqualTo("requirement:" + requirementId.value());
        ArgumentCaptor<ReviewCommandService.StartReviewCommand> startCommand =
                ArgumentCaptor.forClass(ReviewCommandService.StartReviewCommand.class);
        verify(reviewCommandService).start(org.mockito.ArgumentMatchers.eq(reviewId), startCommand.capture());
        assertThat(startCommand.getValue().expectedVersion()).isZero();
        assertThat(startCommand.getValue().idempotencyKey()).isEqualTo("launch-001");
    }

    @Test
    void replaysAnAlreadyStartedLaunchWithoutRebindingOrRestarting() {
        Requirement submitted = submitted(reviewId);
        Review planningReview = Review.restore(reviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of());
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(submitted));
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(planningReview));
        useCompletedReservation(reviewId);

        RequirementReviewLaunchService.LaunchResult result = service.launch(requirementId, command(0L));

        assertThat(result.reviewId()).isEqualTo(reviewId.value());
        assertThat(result.stage()).isEqualTo("PLANNING");
        assertThat(result.replayed()).isTrue();
        verify(intakeService, never()).intake(any());
        verify(requirementCommandService, never()).submitForReview(any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(reviewCommandService, never()).start(any(), any());
    }

    @Test
    void reportsTheReusedReviewWhenThatReviewIsAlreadyBoundToAnotherRequirement() {
        Requirement draft = draft();
        when(intakeService.intake(any())).thenReturn(intakeResult(reviewId, false));
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft));
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenThrow(
                new RequirementDomainException(RequirementErrorCode.REVIEW_ALREADY_BOUND, "review occupied"));

        assertThatThrownBy(() -> service.launch(requirementId, command(0L)))
                .isInstanceOf(RequirementReviewLaunchException.class)
                .satisfies(failure -> {
                    RequirementReviewLaunchException exception = (RequirementReviewLaunchException) failure;
                    assertThat(exception.code()).isEqualTo("REVIEW_ALREADY_BOUND");
                    assertThat(exception.existingReviewId()).isEqualTo(reviewId.value());
                    assertThat(exception.recoverable()).isFalse();
                });

        verify(reviewCommandService, never()).start(any(), any());
    }

    @Test
    void repairsACompletedReservationThatPointsToAnotherRequirementsReview() {
        ReviewId foreignReviewId = new ReviewId(UUID.randomUUID());
        ReviewId replacementReviewId = new ReviewId(UUID.randomUUID());
        Requirement draft = draft();
        Requirement foreignRequirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "Foreign", "Description", "creator", null, "cx-ai", "P1");
        foreignRequirement.submitForReview(foreignReviewId, new RequirementLifecycleStateMachine());
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft));
        when(requirementRepository.findByReviewId(foreignReviewId)).thenReturn(Optional.of(foreignRequirement));
        when(reviewRegistry.find(foreignReviewId)).thenReturn(Optional.of(Review.pending(foreignReviewId)));
        when(intakeService.intake(any()))
                .thenReturn(intakeResult(foreignReviewId, true), intakeResult(replacementReviewId, false));
        when(requirementCommandService.submitForReview(requirementId, foreignReviewId, 0L)).thenThrow(
                new RequirementDomainException(RequirementErrorCode.REVIEW_ALREADY_BOUND, "review occupied"));
        when(requirementCommandService.submitForReview(requirementId, replacementReviewId, 0L))
                .thenReturn(submitted(replacementReviewId));
        when(reviewRegistry.find(replacementReviewId)).thenReturn(Optional.of(Review.pending(replacementReviewId)));
        when(reviewCommandService.start(any(), any())).thenReturn(
                new ReviewCommandService.StartReviewResult(replacementReviewId.value(), 1, 3L, "PLANNING", false));

        assertThatThrownBy(() -> service.launch(requirementId, command(0L)))
                .isInstanceOf(RequirementReviewLaunchException.class)
                .satisfies(failure -> assertThat(((RequirementReviewLaunchException) failure).code())
                        .isEqualTo("REVIEW_ALREADY_BOUND"));
        RequirementReviewLaunchService.LaunchResult result = service.launch(requirementId, command(0L));

        assertThat(result.reviewId()).isEqualTo(replacementReviewId.value());
        verify(intakeService, times(2)).intake(any());
    }

    @Test
    void repairsACompletedReservationForAnUnboundReviewThatAlreadyStarted() {
        ReviewId startedReviewId = new ReviewId(UUID.randomUUID());
        ReviewId replacementReviewId = new ReviewId(UUID.randomUUID());
        Requirement draft = draft();
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft));
        when(requirementRepository.findByReviewId(startedReviewId)).thenReturn(Optional.empty());
        when(reviewRegistry.find(startedReviewId)).thenReturn(Optional.of(
                Review.restore(startedReviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of())));
        when(intakeService.intake(any()))
                .thenReturn(intakeResult(startedReviewId, true), intakeResult(replacementReviewId, false));
        when(requirementCommandService.submitForReview(requirementId, startedReviewId, 0L)).thenThrow(
                new RequirementDomainException(RequirementErrorCode.REVIEW_ALREADY_BOUND, "review not pending"));
        when(requirementCommandService.submitForReview(requirementId, replacementReviewId, 0L))
                .thenReturn(submitted(replacementReviewId));
        when(reviewRegistry.find(replacementReviewId)).thenReturn(Optional.of(Review.pending(replacementReviewId)));
        when(reviewCommandService.start(any(), any())).thenReturn(
                new ReviewCommandService.StartReviewResult(replacementReviewId.value(), 1, 3L, "PLANNING", false));

        assertThatThrownBy(() -> service.launch(requirementId, command(0L)))
                .isInstanceOf(RequirementReviewLaunchException.class)
                .satisfies(failure -> assertThat(((RequirementReviewLaunchException) failure).code())
                        .isEqualTo("REVIEW_ALREADY_BOUND"));
        RequirementReviewLaunchService.LaunchResult result = service.launch(requirementId, command(0L));

        assertThat(result.reviewId()).isEqualTo(replacementReviewId.value());
        verify(intakeService, times(2)).intake(any());
    }

    @Test
    void preservesACompletedReservationWhenAConcurrentNodeAlreadyBoundAndStartedIt() {
        Requirement staleDraft = draft();
        Requirement currentOwner = submitted(reviewId);
        Review planningReview = Review.restore(reviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of());
        when(requirementRepository.findById(requirementId))
                .thenReturn(Optional.of(staleDraft), Optional.of(currentOwner));
        when(requirementRepository.findByReviewId(reviewId)).thenReturn(Optional.of(currentOwner));
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(planningReview));
        useCompletedReservation(reviewId);

        RequirementReviewLaunchService.LaunchResult result = service.launch(requirementId, command(0L));

        assertThat(result.reviewId()).isEqualTo(reviewId.value());
        assertThat(result.stage()).isEqualTo("PLANNING");
        assertThat(result.replayed()).isTrue();
        verify(intakeService, never()).intake(any());
        verify(requirementCommandService, never())
                .submitForReview(any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(reviewCommandService, never()).start(any(), any());
    }

    @Test
    void replaysWhenConcurrentBindingCompletesAfterTheRequirementRefresh() {
        Requirement staleDraft = draft();
        Requirement currentOwner = submitted(reviewId);
        Review planningReview = Review.restore(reviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of());
        when(requirementRepository.findById(requirementId))
                .thenReturn(Optional.of(staleDraft), Optional.of(staleDraft), Optional.of(currentOwner));
        when(requirementRepository.findByReviewId(reviewId)).thenReturn(Optional.of(currentOwner));
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(planningReview));
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenThrow(
                new RequirementDomainException(RequirementErrorCode.VERSION_CONFLICT, "concurrent binding"));
        useCompletedReservation(reviewId);

        RequirementReviewLaunchService.LaunchResult result = service.launch(requirementId, command(0L));

        assertThat(result.reviewId()).isEqualTo(reviewId.value());
        assertThat(result.stage()).isEqualTo("PLANNING");
        assertThat(result.replayed()).isTrue();
        verify(intakeService, never()).intake(any());
        verify(requirementCommandService).submitForReview(requirementId, reviewId, 0L);
        verify(reviewCommandService, never()).start(any(), any());
    }

    @Test
    void replaysWhenConcurrentStartMakesTheReviewNonPendingDuringBinding() {
        Requirement staleDraft = draft();
        Requirement currentOwner = submitted(reviewId);
        Review planningReview = Review.restore(reviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of());
        when(requirementRepository.findById(requirementId))
                .thenReturn(Optional.of(staleDraft), Optional.of(staleDraft), Optional.of(currentOwner));
        when(requirementRepository.findByReviewId(reviewId)).thenReturn(Optional.of(currentOwner));
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(planningReview));
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenThrow(
                new RequirementDomainException(RequirementErrorCode.REVIEW_ALREADY_BOUND, "review not pending"));
        useCompletedReservation(reviewId);

        RequirementReviewLaunchService.LaunchResult result = service.launch(requirementId, command(0L));

        assertThat(result.reviewId()).isEqualTo(reviewId.value());
        assertThat(result.stage()).isEqualTo("PLANNING");
        assertThat(result.replayed()).isTrue();
        verify(intakeService, never()).intake(any());
        verify(requirementCommandService).submitForReview(requirementId, reviewId, 0L);
        verify(reviewCommandService, never()).start(any(), any());
    }

    @Test
    void preservesTheBindingAndMarksSynchronousStartFailureRecoverable() {
        Requirement draft = draft();
        Requirement submitted = submitted(reviewId);
        when(intakeService.intake(any())).thenReturn(intakeResult(reviewId, false));
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft));
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenReturn(submitted);
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(Review.pending(reviewId)));
        when(reviewCommandService.start(any(), any())).thenThrow(new IllegalStateException("runtime unavailable"));

        assertThatThrownBy(() -> service.launch(requirementId, command(0L)))
                .isInstanceOf(RequirementReviewLaunchException.class)
                .satisfies(failure -> {
                    RequirementReviewLaunchException exception = (RequirementReviewLaunchException) failure;
                    assertThat(exception.code()).isEqualTo("REVIEW_START_FAILED");
                    assertThat(exception.phase()).isEqualTo("BOUND");
                    assertThat(exception.existingReviewId()).isEqualTo(reviewId.value());
                    assertThat(exception.recoverable()).isTrue();
                });
    }

    @Test
    void retriesARecordedFailedAttemptAndStartsTheFreshAttempt() {
        Requirement submitted = submitted(reviewId);
        Review failedReview = Review.restore(reviewId, ReviewStage.FAILED, 1, 8L, List.of(), Map.of());
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(submitted));
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(failedReview));
        useCompletedReservation(reviewId);
        when(reviewCommandService.retry(reviewId, 8L)).thenReturn(
                new ReviewCommandService.RetryReviewResult(reviewId.value(), 1, 2, 9L, false));
        when(reviewCommandService.start(any(), any())).thenReturn(
                new ReviewCommandService.StartReviewResult(reviewId.value(), 2, 12L, "PLANNING", false));

        RequirementReviewLaunchService.LaunchResult result = service.launch(requirementId, command(0L));

        assertThat(result.attemptNo()).isEqualTo(2);
        assertThat(result.stage()).isEqualTo("PLANNING");
        assertThat(result.replayed()).isTrue();
        ArgumentCaptor<ReviewCommandService.StartReviewCommand> startCommand =
                ArgumentCaptor.forClass(ReviewCommandService.StartReviewCommand.class);
        verify(reviewCommandService).start(org.mockito.ArgumentMatchers.eq(reviewId), startCommand.capture());
        assertThat(startCommand.getValue().expectedVersion()).isEqualTo(9L);
        verify(intakeService, never()).intake(any());
    }

    @Test
    void rejectsANewLaunchKeyAfterTheRequirementWasAlreadyBound() {
        Requirement submitted = submitted(reviewId);
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(submitted));
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(
                Review.restore(reviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of())));

        assertThatThrownBy(() -> service.launch(requirementId, command(0L)))
                .isInstanceOf(RequirementReviewLaunchException.class)
                .satisfies(failure -> {
                    RequirementReviewLaunchException exception = (RequirementReviewLaunchException) failure;
                    assertThat(exception.code()).isEqualTo("REVIEW_ALREADY_BOUND");
                    assertThat(exception.existingReviewId()).isEqualTo(reviewId.value());
                });

        verify(intakeService, never()).intake(any());
        verify(reviewCommandService, never()).retry(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(reviewCommandService, never()).start(any(), any());
    }

    @Test
    void renewsTheReservationWhileIntakeIsStillRunning() throws Exception {
        RequirementReviewLaunchCommandStore store = mock(RequirementReviewLaunchCommandStore.class);
        when(store.reserve(eq(requirementId), eq("launch-001"), anyString(), any()))
                .thenReturn(Reservation.acquired());
        when(store.renew(eq(requirementId), eq("launch-001"), any())).thenReturn(true);
        when(store.complete(eq(requirementId), eq("launch-001"), anyString(), any(), eq(reviewId)))
                .thenReturn(true);
        CountDownLatch intakeEntered = new CountDownLatch(1);
        CountDownLatch finishIntake = new CountDownLatch(1);
        when(intakeService.intake(any())).thenAnswer(invocation -> {
            intakeEntered.countDown();
            finishIntake.await(1, TimeUnit.SECONDS);
            return intakeResult(reviewId, false);
        });
        Requirement draft = draft();
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft));
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenReturn(submitted(reviewId));
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(Review.pending(reviewId)));
        when(reviewCommandService.start(any(), any())).thenReturn(
                new ReviewCommandService.StartReviewResult(reviewId.value(), 1, 3L, "PLANNING", false));

        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        ExecutorService launchExecutor = Executors.newSingleThreadExecutor();
        try {
            service = new RequirementReviewLaunchService(
                    intakeService,
                    requirementCommandService,
                    reviewCommandService,
                    requirementRepository,
                    reviewRegistry,
                    store,
                    heartbeatExecutor,
                    Duration.ofMillis(5));
            Future<RequirementReviewLaunchService.LaunchResult> result =
                    launchExecutor.submit(() -> service.launch(requirementId, command(0L)));
            assertThat(intakeEntered.await(1, TimeUnit.SECONDS)).isTrue();

            verify(store, org.mockito.Mockito.timeout(500).atLeastOnce())
                    .renew(eq(requirementId), eq("launch-001"), any());
            finishIntake.countDown();
            assertThat(result.get(1, TimeUnit.SECONDS).reviewId()).isEqualTo(reviewId.value());
        } finally {
            finishIntake.countDown();
            launchExecutor.shutdownNow();
            heartbeatExecutor.shutdownNow();
        }
    }

    @Test
    void cancelsIntakeBeforeCompletionWhenTheReservationLeaseIsLost() throws Exception {
        RequirementReviewLaunchCommandStore store = mock(RequirementReviewLaunchCommandStore.class);
        when(store.reserve(eq(requirementId), eq("launch-001"), anyString(), any()))
                .thenReturn(Reservation.acquired());
        when(store.renew(eq(requirementId), eq("launch-001"), any())).thenReturn(false);
        CountDownLatch intakeEntered = new CountDownLatch(1);
        when(intakeService.intake(any())).thenAnswer(invocation -> {
            ReviewIntakeRequest request = invocation.getArgument(0);
            intakeEntered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!request.cancellation().isCancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            request.cancellation().checkCancelled();
            return intakeResult(reviewId, false);
        });
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft()));

        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        ExecutorService launchExecutor = Executors.newSingleThreadExecutor();
        try {
            service = new RequirementReviewLaunchService(
                    intakeService,
                    requirementCommandService,
                    reviewCommandService,
                    requirementRepository,
                    reviewRegistry,
                    store,
                    heartbeatExecutor,
                    Duration.ofMillis(5));
            Future<RequirementReviewLaunchService.LaunchResult> result =
                    launchExecutor.submit(() -> service.launch(requirementId, command(0L)));
            assertThat(intakeEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RequirementReviewLaunchException.class)
                    .satisfies(failure -> assertThat(
                            ((RequirementReviewLaunchException) failure.getCause()).code())
                            .isEqualTo("REVIEW_LAUNCH_IN_PROGRESS"));
            verify(store, never()).complete(any(), anyString(), anyString(), any(), any());
            verify(requirementCommandService, never())
                    .submitForReview(any(), any(), org.mockito.ArgumentMatchers.anyLong());
        } finally {
            launchExecutor.shutdownNow();
            heartbeatExecutor.shutdownNow();
        }
    }

    @Test
    void rejectsRequirementVersionConflictBeforeCreatingAnIntake() {
        Requirement draft = draft();
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.launch(requirementId, command(4L)))
                .isInstanceOf(RequirementDomainException.class)
                .satisfies(failure -> assertThat(((RequirementDomainException) failure).errorCode())
                        .isEqualTo(RequirementErrorCode.VERSION_CONFLICT));
        verify(intakeService, never()).intake(any());
        verify(requirementCommandService, never()).submitForReview(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsMissingRequirementBeforeCreatingAnIntake() {
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.launch(requirementId, command(0L)))
                .isInstanceOf(RequirementDomainException.class)
                .satisfies(failure -> assertThat(((RequirementDomainException) failure).errorCode())
                        .isEqualTo(RequirementErrorCode.REQUIREMENT_NOT_FOUND));
        verify(intakeService, never()).intake(any());
    }

    @Test
    void replaysTheCompletedLaunchAfterServiceReconstructionWithoutAnotherIntake() {
        Requirement draft = draft();
        Requirement submitted = submitted(reviewId);
        AtomicReference<Requirement> persistedRequirement = new AtomicReference<>(draft);
        AtomicReference<Review> persistedReview = new AtomicReference<>(Review.pending(reviewId));
        when(requirementRepository.findById(requirementId)).thenAnswer(invocation -> Optional.of(persistedRequirement.get()));
        when(intakeService.intake(any())).thenReturn(intakeResult(reviewId, false));
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenAnswer(invocation -> {
            persistedRequirement.set(submitted);
            return submitted;
        });
        when(reviewRegistry.find(reviewId)).thenAnswer(invocation -> Optional.of(persistedReview.get()));
        when(reviewCommandService.start(any(), any())).thenAnswer(invocation -> {
            persistedReview.set(Review.restore(reviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of()));
            return new ReviewCommandService.StartReviewResult(reviewId.value(), 1, 3L, "PLANNING", false);
        });
        service.launch(requirementId, command(0L));
        RequirementReviewLaunchService reconstructed = new RequirementReviewLaunchService(
                intakeService,
                requirementCommandService,
                reviewCommandService,
                requirementRepository,
                reviewRegistry,
                launchCommandStore);

        RequirementReviewLaunchService.LaunchResult replay = reconstructed.launch(requirementId, command(0L));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reviewId()).isEqualTo(reviewId.value());
        verify(intakeService, times(1)).intake(any());
    }

    @Test
    void rejectsTheSameIdempotencyKeyWithAnotherFileBeforeAnotherIntake() {
        Requirement draft = draft();
        Requirement submitted = submitted(reviewId);
        AtomicReference<Requirement> persistedRequirement = new AtomicReference<>(draft);
        when(requirementRepository.findById(requirementId)).thenAnswer(invocation -> Optional.of(persistedRequirement.get()));
        when(intakeService.intake(any())).thenReturn(intakeResult(reviewId, false));
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenAnswer(invocation -> {
            persistedRequirement.set(submitted);
            return submitted;
        });
        when(reviewRegistry.find(reviewId)).thenReturn(
                Optional.of(Review.restore(reviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of())));
        when(reviewCommandService.start(any(), any())).thenReturn(
                new ReviewCommandService.StartReviewResult(reviewId.value(), 1, 3L, "PLANNING", false));
        service.launch(requirementId, commandWithFile("first.md", "# First"));

        assertThatThrownBy(() -> service.launch(requirementId, commandWithFile("second.md", "# Second")))
                .isInstanceOf(RequirementReviewLaunchException.class)
                .satisfies(failure -> assertThat(((RequirementReviewLaunchException) failure).code())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verify(intakeService, times(1)).intake(any());
    }

    @Test
    void serializesConcurrentCommandsWithTheSameKeyBeforeDifferentFilesCanCreateTwoReviews() throws Exception {
        Requirement draft = draft();
        AtomicInteger intakeCount = new AtomicInteger();
        AtomicReference<Review> activeReview = new AtomicReference<>();
        when(requirementRepository.findById(requirementId)).thenReturn(Optional.of(draft));
        when(intakeService.intake(any())).thenAnswer(invocation -> {
            intakeCount.incrementAndGet();
            Review pending = Review.pending(reviewId);
            activeReview.set(pending);
            return intakeResult(reviewId, false);
        });
        when(requirementCommandService.submitForReview(requirementId, reviewId, 0L)).thenAnswer(invocation -> {
            draft.submitForReview(reviewId, new RequirementLifecycleStateMachine());
            return draft;
        });
        when(reviewRegistry.find(reviewId)).thenAnswer(invocation -> Optional.ofNullable(activeReview.get()));
        when(reviewCommandService.start(any(), any())).thenAnswer(invocation -> {
            activeReview.set(Review.restore(reviewId, ReviewStage.PLANNING, 1, 3L, List.of(), Map.of()));
            return new ReviewCommandService.StartReviewResult(reviewId.value(), 1, 3L, "PLANNING", false);
        });
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> {
                start.await(1, TimeUnit.SECONDS);
                return launchOutcome(commandWithFile("first.md", "# First"));
            });
            Future<Object> second = executor.submit(() -> {
                start.await(1, TimeUnit.SECONDS);
                return launchOutcome(commandWithFile("second.md", "# Second"));
            });
            start.countDown();

            List<Object> outcomes = List.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(RequirementReviewLaunchService.LaunchResult.class::isInstance).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(RequirementReviewLaunchException.class::isInstance)
                    .singleElement()
                    .satisfies(failure -> assertThat(((RequirementReviewLaunchException) failure).code())
                            .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        }

        assertThat(intakeCount).hasValue(1);
        verify(requirementCommandService).submitForReview(requirementId, reviewId, 0L);
    }

    private Object launchOutcome(RequirementReviewLaunchService.LaunchCommand command) {
        try {
            return service.launch(requirementId, command);
        } catch (RequirementReviewLaunchException exception) {
            return exception;
        }
    }

    private void useCompletedReservation(ReviewId targetReviewId) {
        RequirementReviewLaunchCommandStore store = mock(RequirementReviewLaunchCommandStore.class);
        when(store.reserve(eq(requirementId), eq("launch-001"), anyString(), any()))
                .thenReturn(Reservation.replay(targetReviewId));
        service = new RequirementReviewLaunchService(
                intakeService,
                requirementCommandService,
                reviewCommandService,
                requirementRepository,
                reviewRegistry,
                store);
    }

    private RequirementReviewLaunchService.LaunchCommand command(long expectedVersion) {
        return commandWithFile("requirement.md", "# Requirement", expectedVersion);
    }

    private RequirementReviewLaunchService.LaunchCommand commandWithFile(String filename, String content) {
        return commandWithFile(filename, content, 0L);
    }

    private RequirementReviewLaunchService.LaunchCommand commandWithFile(
            String filename, String content, long expectedVersion) {
        return new RequirementReviewLaunchService.LaunchCommand(
                new IntakeDocument(filename, content.getBytes(StandardCharsets.UTF_8)),
                "cx-ai",
                "main",
                null,
                "product-owner",
                expectedVersion,
                "launch-001",
                "trace-001",
                List.of("Review requirement"),
                "Initial review",
                "Begin review");
    }

    private Requirement draft() {
        return Requirement.draft(requirementId, "Requirement", "Description", "creator", null, "cx-ai", "P1");
    }

    private Requirement submitted(ReviewId targetReviewId) {
        Requirement requirement = draft();
        requirement.submitForReview(targetReviewId, new RequirementLifecycleStateMachine());
        return requirement;
    }

    private ReviewIntakeResult intakeResult(ReviewId targetReviewId, boolean reused) {
        RequirementSnapshot snapshot = new RequirementSnapshot(
                UUID.randomUUID(),
                targetReviewId,
                1,
                "product-owner",
                "cx-ai",
                "main",
                null,
                "requirement.md",
                "a".repeat(64),
                "b".repeat(64),
                "markdown-line-parser-v1",
                new RequirementDocument(List.of(), List.of(), 0, 0, false),
                Instant.now());
        return new ReviewIntakeResult(
                snapshot,
                new StoredRequirementSnapshot(Path.of("raw.md"), Path.of("normalized.md"), Path.of("snapshot.json")),
                reused);
    }
}
