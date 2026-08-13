package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore.Reservation;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore.ReservationStatus;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * [AIREVIEW-PLAN-023#3] Idempotently intakes, binds and starts a review from one draft command.
 *
 * @author zyj
 */
@Service
public class RequirementReviewLaunchService {

    private static final int LAUNCH_LOCK_STRIPES = 256;
    private static final Object[] LAUNCH_LOCKS = IntStream.range(0, LAUNCH_LOCK_STRIPES)
            .mapToObj(ignored -> new Object())
            .toArray();
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final ScheduledExecutorService HEARTBEAT_EXECUTOR = Executors.newScheduledThreadPool(
            2,
            runnable -> Thread.ofPlatform()
                    .daemon(true)
                    .name("requirement-review-launch-heartbeat")
                    .unstarted(runnable));

    private final ReviewIntakeService intakeService;
    private final RequirementCommandService requirementCommandService;
    private final ReviewCommandService reviewCommandService;
    private final RequirementRepository requirementRepository;
    private final ReviewRegistry reviewRegistry;
    private final RequirementReviewLaunchCommandStore launchCommandStore;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Duration heartbeatInterval;

    @Autowired
    public RequirementReviewLaunchService(
            ReviewIntakeService intakeService,
            RequirementCommandService requirementCommandService,
            ReviewCommandService reviewCommandService,
            RequirementRepository requirementRepository,
            ReviewRegistry reviewRegistry,
            RequirementReviewLaunchCommandStore launchCommandStore) {
        this(
                intakeService,
                requirementCommandService,
                reviewCommandService,
                requirementRepository,
                reviewRegistry,
                launchCommandStore,
                HEARTBEAT_EXECUTOR,
                DEFAULT_HEARTBEAT_INTERVAL);
    }

    RequirementReviewLaunchService(
            ReviewIntakeService intakeService,
            RequirementCommandService requirementCommandService,
            ReviewCommandService reviewCommandService,
            RequirementRepository requirementRepository,
            ReviewRegistry reviewRegistry,
            RequirementReviewLaunchCommandStore launchCommandStore,
            ScheduledExecutorService heartbeatExecutor,
            Duration heartbeatInterval) {
        this.intakeService = Objects.requireNonNull(intakeService, "intakeService must not be null");
        this.requirementCommandService = Objects.requireNonNull(
                requirementCommandService, "requirementCommandService must not be null");
        this.reviewCommandService = Objects.requireNonNull(reviewCommandService, "reviewCommandService must not be null");
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.launchCommandStore = Objects.requireNonNull(launchCommandStore, "launchCommandStore must not be null");
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor must not be null");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval must not be null");
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
    }

    public LaunchResult launch(RequirementId requirementId, LaunchCommand command) {
        RequirementId targetRequirementId = Objects.requireNonNull(requirementId, "requirementId must not be null");
        LaunchCommand targetCommand = Objects.requireNonNull(command, "command must not be null");
        synchronized (lockFor(targetRequirementId)) {
            return launchSerially(targetRequirementId, targetCommand);
        }
    }

    private LaunchResult launchSerially(RequirementId requirementId, LaunchCommand command) {
        Requirement requirement = requireRequirement(requirementId);
        if (requirement.reviewId() == null) {
            requirement.requireExpectedVersion(command.expectedVersion());
        }
        String requestFingerprint = fingerprint(command);
        UUID ownerToken = UUID.randomUUID();
        Reservation reservation = launchCommandStore.reserve(
                requirementId, command.idempotencyKey(), requestFingerprint, ownerToken);
        if (reservation.status() == ReservationStatus.REPLAY
                && isInvalidCompletedReservation(requirement, reservation.reviewId())) {
            if (!launchCommandStore.invalidateCompleted(
                    requirementId,
                    command.idempotencyKey(),
                    requestFingerprint,
                    reservation.reviewId())) {
                throw RequirementReviewLaunchException.launchInProgress();
            }
            reservation = launchCommandStore.reserve(
                    requirementId, command.idempotencyKey(), requestFingerprint, ownerToken);
        }
        if (reservation.status() == ReservationStatus.CONFLICT) {
            throw RequirementReviewLaunchException.idempotencyKeyReused(
                    requirement.reviewId() == null ? null : requirement.reviewId().value());
        }
        if (reservation.status() == ReservationStatus.IN_PROGRESS) {
            throw RequirementReviewLaunchException.launchInProgress();
        }
        if (reservation.status() == ReservationStatus.REPLAY) {
            return continueReservedLaunch(requireRequirement(requirementId), reservation.reviewId(), command);
        }
        if (requirement.reviewId() != null) {
            launchCommandStore.release(requirement.id(), command.idempotencyKey(), ownerToken);
            throw RequirementReviewLaunchException.alreadyBound(requirement.reviewId().value());
        }
        return executeReservedLaunch(requirement, command, requestFingerprint, ownerToken);
    }

    private boolean isInvalidCompletedReservation(Requirement requirement, ReviewId reservedReviewId) {
        if (requirement.reviewId() != null || reservedReviewId == null) {
            return false;
        }
        Requirement owner = requirementRepository.findByReviewId(reservedReviewId).orElse(null);
        if (owner != null) {
            return !owner.id().equals(requirement.id());
        }
        Review reservedReview = reviewRegistry.find(reservedReviewId).orElse(null);
        return reservedReview == null || reservedReview.stage() != ReviewStage.PENDING;
    }

    private LaunchResult executeReservedLaunch(
            Requirement requirement, LaunchCommand command, String requestFingerprint, UUID ownerToken) {
        ReviewIntakeResult intake;
        ReservationHeartbeat heartbeat = startHeartbeat(requirement.id(), command.idempotencyKey(), ownerToken);
        try (heartbeat) {
            intake = intakeService.intake(new ReviewIntakeRequest(
                    command.requirementFile(),
                    command.repositoryPath(),
                    command.branch(),
                    command.commit(),
                    command.submitter(),
                    false,
                    "requirement:" + requirement.id().value(),
                    heartbeat));
            heartbeat.checkCancelled();
            ReviewId targetReviewId = intake.snapshot().reviewId();
            completeReservation(
                    requirement.id(), command.idempotencyKey(), requestFingerprint, ownerToken, targetReviewId);
        } catch (RuntimeException exception) {
            launchCommandStore.release(requirement.id(), command.idempotencyKey(), ownerToken);
            if (heartbeat.isCancelled()) {
                throw RequirementReviewLaunchException.launchInProgress();
            }
            throw exception;
        }
        ReviewId targetReviewId = intake.snapshot().reviewId();
        Requirement boundRequirement = bind(requirement, targetReviewId, command.expectedVersion());
        return startOrReplay(boundRequirement, targetReviewId, command, intake.reused());
    }

    private LaunchResult continueReservedLaunch(
            Requirement requirement, ReviewId reservedReviewId, LaunchCommand command) {
        if (requirement.reviewId() != null && !requirement.reviewId().equals(reservedReviewId)) {
            throw RequirementReviewLaunchException.alreadyBound(requirement.reviewId().value());
        }
        Requirement boundRequirement = requirement.reviewId() == null
                ? bind(requirement, reservedReviewId, command.expectedVersion())
                : requirement;
        return startOrReplay(boundRequirement, reservedReviewId, command, true);
    }

    private void completeReservation(
            RequirementId requirementId,
            String idempotencyKey,
            String requestFingerprint,
            UUID ownerToken,
            ReviewId reviewId) {
        if (!launchCommandStore.complete(
                requirementId, idempotencyKey, requestFingerprint, ownerToken, reviewId)) {
            throw RequirementReviewLaunchException.launchInProgress();
        }
    }

    private LaunchResult startOrReplay(
            Requirement boundRequirement, ReviewId targetReviewId, LaunchCommand command, boolean bindingReplay) {
        Review review = requireReview(targetReviewId);

        if (review.stage() != ReviewStage.PENDING && review.stage() != ReviewStage.FAILED) {
            return replayed(boundRequirement, review);
        }

        long startVersion = review.version();
        boolean recoveredAttempt = false;
        if (review.stage() == ReviewStage.FAILED) {
            ReviewCommandService.RetryReviewResult retry = reviewCommandService.retry(targetReviewId, review.version());
            startVersion = retry.version();
            recoveredAttempt = true;
        }

        try {
            ReviewCommandService.StartReviewResult started = reviewCommandService.start(
                    targetReviewId,
                    new ReviewCommandService.StartReviewCommand(
                            startVersion,
                            command.idempotencyKey(),
                            command.submitter(),
                            command.traceId(),
                            command.publicTasks(),
                            command.changeReason(),
                            command.initialMessage()));
            return new LaunchResult(
                    boundRequirement.id().value(),
                    started.reviewId(),
                    started.attemptNo(),
                    boundRequirement.version(),
                    started.version(),
                    boundRequirement.status().name(),
                    started.stage(),
                    "STARTED",
                    false,
                    bindingReplay || recoveredAttempt || started.replayed(),
                    "/reviews/" + started.reviewId() + "/live");
        } catch (RequirementDomainException exception) {
            Review latest = reviewRegistry.find(targetReviewId).orElse(review);
            if (latest.stage() != ReviewStage.PENDING && latest.stage() != ReviewStage.FAILED) {
                return replayed(boundRequirement, latest);
            }
            throw RequirementReviewLaunchException.startFailed(targetReviewId.value(), exception);
        } catch (RuntimeException exception) {
            throw RequirementReviewLaunchException.startFailed(targetReviewId.value(), exception);
        }
    }

    private Object lockFor(RequirementId requirementId) {
        return LAUNCH_LOCKS[Math.floorMod(requirementId.hashCode(), LAUNCH_LOCK_STRIPES)];
    }

    private ReservationHeartbeat startHeartbeat(
            RequirementId requirementId, String idempotencyKey, UUID ownerToken) {
        long intervalMillis = heartbeatInterval.toMillis();
        ReservationHeartbeat heartbeat = new ReservationHeartbeat(
                launchCommandStore, requirementId, idempotencyKey, ownerToken);
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(
                heartbeat::renew,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS);
        heartbeat.attach(future);
        return heartbeat;
    }

    private String fingerprint(LaunchCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, command.requirementFile().getBytes());
            update(digest, command.repositoryPath());
            update(digest, command.branch());
            update(digest, command.commit());
            update(digest, command.submitter());
            update(digest, Long.toString(command.expectedVersion()));
            command.publicTasks().forEach(task -> update(digest, task));
            update(digest, command.changeReason());
            update(digest, command.initialMessage());
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw RequirementReviewLaunchException.unreadableUpload();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        update(digest, value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private Requirement bind(Requirement requirement, ReviewId targetReviewId, long expectedVersion) {
        synchronized (requirement) {
            if (requirement.reviewId() != null) {
                if (!requirement.reviewId().equals(targetReviewId)) {
                    throw RequirementReviewLaunchException.alreadyBound(requirement.reviewId().value());
                }
                return requirement;
            }
            try {
                return requirementCommandService.submitForReview(requirement.id(), targetReviewId, expectedVersion);
            } catch (RequirementDomainException exception) {
                if (exception.errorCode() == RequirementErrorCode.VERSION_CONFLICT
                        || exception.errorCode() == RequirementErrorCode.REVIEW_ALREADY_BOUND) {
                    Requirement latest = requireRequirement(requirement.id());
                    if (targetReviewId.equals(latest.reviewId())) {
                        return latest;
                    }
                    if (latest.reviewId() != null) {
                        throw RequirementReviewLaunchException.alreadyBound(latest.reviewId().value());
                    }
                }
                if (exception.errorCode() == RequirementErrorCode.REVIEW_ALREADY_BOUND) {
                    throw RequirementReviewLaunchException.reviewBindingConflict(targetReviewId.value());
                }
                throw exception;
            }
        }
    }

    private Requirement requireRequirement(RequirementId requirementId) {
        return requirementRepository.findById(requirementId)
                .orElseThrow(() -> new RequirementDomainException(
                        RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement was not found"));
    }

    private Review requireReview(ReviewId reviewId) {
        return reviewRegistry.find(reviewId)
                .orElseThrow(() -> RequirementReviewLaunchException.startFailed(
                        reviewId.value(), new IllegalStateException("review registry entry was not found")));
    }

    private LaunchResult replayed(Requirement requirement, Review review) {
        return new LaunchResult(
                requirement.id().value(),
                review.id().value(),
                review.attemptNo(),
                requirement.version(),
                review.version(),
                requirement.status().name(),
                review.stage().name(),
                "STARTED",
                false,
                true,
                "/reviews/" + review.id().value() + "/live");
    }

    /**
     * [AIREVIEW-PLAN-023#3] Validated launch input shared by the multipart adapter and application service.
     *
     * @author zyj
     */
    public record LaunchCommand(
            MultipartFile requirementFile,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            long expectedVersion,
            String idempotencyKey,
            String traceId,
            List<String> publicTasks,
            String changeReason,
            String initialMessage) {

        public LaunchCommand {
            Objects.requireNonNull(requirementFile, "requirementFile must not be null");
            repositoryPath = requireText(repositoryPath, "repositoryPath");
            branch = normalizeOptional(branch);
            commit = normalizeOptional(commit);
            submitter = requireText(submitter, "submitter");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
            if (idempotencyKey.length() > 128) {
                throw new IllegalArgumentException("idempotencyKey must not exceed 128 characters");
            }
            traceId = requireText(traceId, "traceId");
            publicTasks = List.copyOf(Objects.requireNonNull(publicTasks, "publicTasks must not be null"));
            if (publicTasks.isEmpty()) {
                throw new IllegalArgumentException("publicTasks must not be empty");
            }
            publicTasks = publicTasks.stream().map(task -> requireText(task, "publicTask")).toList();
            changeReason = requireText(changeReason, "changeReason");
            initialMessage = requireText(initialMessage, "initialMessage");
        }

        private static String normalizeOptional(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }
    }

    /**
     * [AIREVIEW-PLAN-023#3] Reports the durable binding and asynchronous start acknowledgement.
     *
     * @author zyj
     */
    public record LaunchResult(
            UUID requirementId,
            UUID reviewId,
            int attemptNo,
            long requirementVersion,
            long reviewVersion,
            String requirementStatus,
            String stage,
            String phase,
            boolean recoverable,
            boolean replayed,
            String liveUrl) {
    }

    private static final class ReservationHeartbeat implements IntakeCancellation, AutoCloseable {

        private final RequirementReviewLaunchCommandStore store;
        private final RequirementId requirementId;
        private final String idempotencyKey;
        private final UUID ownerToken;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;

        private ReservationHeartbeat(
                RequirementReviewLaunchCommandStore store,
                RequirementId requirementId,
                String idempotencyKey,
                UUID ownerToken) {
            this.store = store;
            this.requirementId = requirementId;
            this.idempotencyKey = idempotencyKey;
            this.ownerToken = ownerToken;
        }

        private void attach(ScheduledFuture<?> future) {
            this.future = Objects.requireNonNull(future, "future must not be null");
        }

        private void renew() {
            if (cancelled.get()) {
                return;
            }
            try {
                if (!store.renew(requirementId, idempotencyKey, ownerToken)) {
                    cancelled.set(true);
                }
            } catch (RuntimeException exception) {
                cancelled.set(true);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void close() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }
}
