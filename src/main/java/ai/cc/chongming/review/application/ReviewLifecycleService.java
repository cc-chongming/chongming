package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionActor;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * [AIREVIEW-PLAN-010#1.6][AIREVIEW-PLAN-010#1.7] Applies idempotent cancel/retry transitions at safe domain boundaries.
 *
 * @author wangli
 */
@Service
public class ReviewLifecycleService {

    private final ReviewDebateStore debateStore;
    private final ReviewStateMachine stateMachine;
    private final ReviewEventPublisher eventPublisher;
    private final Map<RetryKey, RetryResult> retries = new ConcurrentHashMap<>();

    public ReviewLifecycleService(
            ReviewDebateStore debateStore,
            ReviewStateMachine stateMachine,
            ReviewEventPublisher eventPublisher) {
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /**
     * Validates cancellation before an adapter asks active agents to stop at a safe point.
     */
    public synchronized void validateCancellation(Review review, long expectedVersion) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.stage() == ReviewStage.CANCELLED) {
            return;
        }
        rejectCancellationAfterHumanDecision(review);
        requireExpectedVersion(review, expectedVersion);
        if (review.stage().isTerminal()) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "a terminal review cannot be cancelled");
        }
    }

    /**
     * Applies cancellation after the caller has brought its runtime to a safe point. Repeating a completed cancel is safe.
     */
    public synchronized CancelResult cancel(Review review, long expectedVersion) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.stage() == ReviewStage.CANCELLED) {
            return new CancelResult(review.id().value(), review.attemptNo(), review.version(), true);
        }
        validateCancellation(review, expectedVersion);
        review.transitionTo(stateMachine, ReviewStage.CANCELLING);
        review.transitionTo(stateMachine, ReviewStage.CANCELLED);
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ReviewEventType.REVIEW_CANCELLED,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                100,
                Map.of("expectedVersion", Long.toString(expectedVersion))));
        return new CancelResult(review.id().value(), review.attemptNo(), review.version(), false);
    }

    /**
     * Creates a fresh attempt only from a terminal one. The prior attempt's runtime/idempotency state is cleared by Review.
     */
    public synchronized RetryResult retry(Review review, long expectedVersion) {
        Objects.requireNonNull(review, "review must not be null");
        RetryKey retryKey = new RetryKey(review.id().value(), expectedVersion);
        RetryResult replayed = retries.get(retryKey);
        if (replayed != null && review.stage() == ReviewStage.PENDING && review.attemptNo() == replayed.attemptNo()) {
            return replayed.asReplayed();
        }
        requireExpectedVersion(review, expectedVersion);
        if (!review.stage().isTerminal()) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "retry requires a terminal review attempt");
        }
        int previousAttempt = review.attemptNo();
        review.startNewAttempt();
        RetryResult result = new RetryResult(review.id().value(), previousAttempt, review.attemptNo(), review.version(), false);
        retries.put(retryKey, result);
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ReviewEventType.REVIEW_RETRIED,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                0,
                Map.of("previousAttempt", Integer.toString(previousAttempt))));
        return result;
    }

    private void rejectCancellationAfterHumanDecision(Review review) {
        GateDecision gate = debateStore.findGateDraft(review.id()).orElse(null);
        if (gate != null && gate.status() == DecisionStatus.FINAL && gate.actor() == DecisionActor.HUMAN) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "a review with a final human decision cannot be cancelled");
        }
    }

    private void requireExpectedVersion(Review review, long expectedVersion) {
        if (expectedVersion != review.version()) {
            throw new ReviewDomainException(ReviewErrorCode.VERSION_CONFLICT,
                    "expectedVersion does not match aggregate version");
        }
    }

    private record RetryKey(java.util.UUID reviewId, long expectedVersion) {
    }

    /**
     * @author wangli
     */
    public record CancelResult(java.util.UUID reviewId, int attemptNo, long version, boolean replayed) {
    }

    /**
     * @author wangli
     */
    public record RetryResult(
            java.util.UUID reviewId,
            int previousAttempt,
            int attemptNo,
            long version,
            boolean replayed) {

        private RetryResult asReplayed() {
            return new RetryResult(reviewId, previousAttempt, attemptNo, version, true);
        }
    }
}
