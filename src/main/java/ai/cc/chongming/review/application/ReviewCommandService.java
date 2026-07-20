package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.config.ReviewDiagnosticsProperties;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * [AIREVIEW-PLAN-010#1.6,#1.7] Accepts review lifecycle commands at the application boundary and keeps HTTP adapters out of domain mutation.
 *
 * <p>The registry backing this service is process-local until the persistent command write model is enabled. A successful start is therefore
 * accepted asynchronously and reports subsequent progress through the existing domain-event/SSE path.
 *
 * @author wangli
 */
@Service
public class ReviewCommandService {

    private static final String START_IDEMPOTENCY_PREFIX = "http-start:";
    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewCommandService.class);

    private final ReviewRegistry reviewRegistry;
    private final ReviewLifecycleService lifecycleService;
    private final ReviewOrchestrationService orchestrationService;
    private final ReviewStateMachine stateMachine;
    private final ReviewEventPublisher eventPublisher;
    private final ReviewDiagnosticsProperties diagnosticsProperties;

    public ReviewCommandService(
            ReviewRegistry reviewRegistry,
            ReviewLifecycleService lifecycleService,
            ReviewOrchestrationService orchestrationService,
            ReviewStateMachine stateMachine,
            ReviewEventPublisher eventPublisher) {
        this(reviewRegistry, lifecycleService, orchestrationService, stateMachine, eventPublisher,
                new ReviewDiagnosticsProperties(false));
    }

    @Autowired
    public ReviewCommandService(
            ReviewRegistry reviewRegistry,
            ReviewLifecycleService lifecycleService,
            ReviewOrchestrationService orchestrationService,
            ReviewStateMachine stateMachine,
            ReviewEventPublisher eventPublisher,
            ReviewDiagnosticsProperties diagnosticsProperties) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService must not be null");
        this.orchestrationService = Objects.requireNonNull(orchestrationService, "orchestrationService must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.diagnosticsProperties = Objects.requireNonNull(diagnosticsProperties, "diagnosticsProperties must not be null");
    }

    /**
     * Records one idempotent start command, then launches the director workflow outside the HTTP request thread.
     */
    public StartReviewResult start(ReviewId reviewId, StartReviewCommand command) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Review review = requireReview(reviewId);
        ReviewRuntimeContext context;
        StartReviewResult result;
        synchronized (review) {
            IdempotencyKey idempotencyKey = new IdempotencyKey(START_IDEMPOTENCY_PREFIX + command.idempotencyKey());
            if (review.commandResults().containsKey(idempotencyKey)) {
                return StartReviewResult.replayed(review);
            }
            requireExpectedVersion(review, command.expectedVersion());
            if (review.stage() != ReviewStage.PENDING) {
                throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                        "a review can start only from PENDING");
            }
            review.recordCommand(new ReviewCommandMetadata(reviewId, command.expectedVersion(), idempotencyKey),
                    "start-attempt-" + review.attemptNo());
            review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
            review.transitionTo(stateMachine, ReviewStage.PLANNING);
            context = new ReviewRuntimeContext(
                    review.id(), review.attemptNo(), command.userId(), command.traceId(), IntakeCancellation.neverCancelled());
            result = StartReviewResult.accepted(review);
        }
        launch(review, context, command);
        return result;
    }

    /**
     * Requests runtime interruption before the domain service commits the terminal cancellation transition.
     */
    public Mono<CancelReviewResult> cancel(ReviewId reviewId, long expectedVersion) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Review review = requireReview(reviewId);
        synchronized (review) {
            if (review.stage() == ReviewStage.CANCELLED) {
                return Mono.just(toResult(lifecycleService.cancel(review, expectedVersion)));
            }
            lifecycleService.validateCancellation(review, expectedVersion);
        }
        return orchestrationService.requestRuntimeCancellation(review.id(), review.attemptNo())
                .then(Mono.fromSupplier(() -> toResult(lifecycleService.cancel(review, expectedVersion))));
    }

    /**
     * Creates an isolated pending attempt; callers use the normal start command to launch it.
     */
    public RetryReviewResult retry(ReviewId reviewId, long expectedVersion) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        ReviewLifecycleService.RetryResult result = lifecycleService.retry(requireReview(reviewId), expectedVersion);
        return new RetryReviewResult(result.reviewId(), result.previousAttempt(), result.attemptNo(), result.version(), result.replayed());
    }

    private void launch(Review review, ReviewRuntimeContext context, StartReviewCommand command) {
        Mono.defer(() -> orchestrationService.start(new ReviewOrchestrationService.StartRequest(
                        review,
                        context,
                        command.publicTasks(),
                        command.changeReason(),
                        command.initialMessage())))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(ignored -> { }, failure -> recordStartupFailure(review, failure));
    }

    private void recordStartupFailure(Review review, Throwable failure) {
        logStartupFailure(review, failure);
        synchronized (review) {
            if (review.stage().isTerminal() || !stateMachine.canTransition(review.stage(), ReviewStage.FAILED)) {
                return;
            }
            review.transitionTo(stateMachine, ReviewStage.FAILED);
            eventPublisher.publish(ReviewEventDrafts.completedCommand(
                    review,
                    ReviewEventType.REVIEW_FAILED,
                    RoleType.DIRECTOR,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of("failureType", failure.getClass().getSimpleName())));
        }
    }

    private void logStartupFailure(Review review, Throwable failure) {
        if (diagnosticsProperties.logStartupFailureStack()) {
            LOGGER.error(
                    "REVIEW_STARTUP_FAILED reviewId={} attempt={} stage={} failureType={} message={}\n{}",
                    review.id().value(),
                    review.attemptNo(),
                    review.stage(),
                    failure.getClass().getSimpleName(),
                    redactFailureMessage(failure),
                    redactedStackTrace(failure));
            return;
        }
        LOGGER.error(
                "REVIEW_STARTUP_FAILED reviewId={} attempt={} stage={} failureType={}",
                review.id().value(),
                review.attemptNo(),
                review.stage(),
                failure.getClass().getSimpleName());
    }

    private String redactFailureMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "No failure message";
        }
        return redactDiagnosticText(message, 500);
    }

    private String redactedStackTrace(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return redactDiagnosticText(writer.toString(), 10_000);
    }

    static String redactDiagnosticText(String value, int limit) {
        String redacted = value
                .replaceAll("(?i)authorization\\s*[=:]\\s*bearer\\s+\\S+", "Authorization=[REDACTED]")
                .replaceAll("(?i)\\bbearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)(password|api[_-]?key|authorization|token)\\s*[=:]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("(?i)(https?://)[^/@\\s]+@", "$1[REDACTED]@")
                .replaceAll("(?i)([?&](?:password|api[_-]?key|token)=)[^&#\\s]+", "$1[REDACTED]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]+", "[REDACTED]");
        return redacted.length() <= limit ? redacted : redacted.substring(0, limit);
    }

    private Review requireReview(ReviewId reviewId) {
        return reviewRegistry.find(reviewId).orElseThrow(() -> new ReviewCommandNotFoundException(reviewId.value()));
    }

    private void requireExpectedVersion(Review review, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (review.version() != expectedVersion) {
            throw new ReviewDomainException(ReviewErrorCode.VERSION_CONFLICT,
                    "expectedVersion does not match aggregate version");
        }
    }

    private CancelReviewResult toResult(ReviewLifecycleService.CancelResult result) {
        return new CancelReviewResult(result.reviewId(), result.attemptNo(), result.version(), result.replayed());
    }

    /**
     * Request data required to create an AgentScope runtime context and a public first-round plan.
     *
     * @author wangli
     */
    public record StartReviewCommand(
            long expectedVersion,
            String idempotencyKey,
            String userId,
            String traceId,
            List<String> publicTasks,
            String changeReason,
            String initialMessage) {

        public StartReviewCommand {
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
            requireText(idempotencyKey, "idempotencyKey");
            requireText(userId, "userId");
            requireText(traceId, "traceId");
            publicTasks = List.copyOf(Objects.requireNonNull(publicTasks, "publicTasks must not be null"));
            if (publicTasks.isEmpty()) {
                throw new IllegalArgumentException("publicTasks must not be empty");
            }
            publicTasks.forEach(task -> requireText(task, "publicTask"));
            requireText(changeReason, "changeReason");
            requireText(initialMessage, "initialMessage");
        }
    }

    /**
     * Immediate acknowledgement for a background start command.
     *
     * @author wangli
     */
    public record StartReviewResult(UUID reviewId, int attemptNo, long version, String stage, boolean replayed) {

        private static StartReviewResult accepted(Review review) {
            return new StartReviewResult(review.id().value(), review.attemptNo(), review.version(), review.stage().name(), false);
        }

        private static StartReviewResult replayed(Review review) {
            return new StartReviewResult(review.id().value(), review.attemptNo(), review.version(), review.stage().name(), true);
        }
    }

    /**
     * Final cancellation outcome after the runtime safe-point request completes.
     *
     * @author wangli
     */
    public record CancelReviewResult(UUID reviewId, int attemptNo, long version, boolean replayed) {
    }

    /**
     * Fresh-attempt metadata after a retry command.
     *
     * @author wangli
     */
    public record RetryReviewResult(
            UUID reviewId, int previousAttempt, int attemptNo, long version, boolean replayed) {
    }

    /**
     * Signals that an HTTP command referred to no review retained by the active command store.
     *
     * @author wangli
     */
    public static final class ReviewCommandNotFoundException extends RuntimeException {

        public ReviewCommandNotFoundException(UUID reviewId) {
            super("review was not found: " + reviewId);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
