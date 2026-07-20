package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import java.util.Map;
import java.util.Objects;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-009#1.4][AIREVIEW-PLAN-010#1.5] Advances a review only after every core role
 * explicitly completes its first independent review.
 *
 * @author wangli
 */
@org.springframework.stereotype.Service
public class InitialReviewProgressService {

    private final ReviewProtocolGuard protocolGuard;
    private final ReviewStateMachine stateMachine;
    private final ReviewEventPublisher eventPublisher;

    public InitialReviewProgressService(
            ReviewProtocolGuard protocolGuard,
            ReviewStateMachine stateMachine,
            ReviewEventPublisher eventPublisher) {
        this.protocolGuard = Objects.requireNonNull(protocolGuard, "protocolGuard must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /**
     * Records an explicit no-finding completion. Claim submissions use {@link #afterRoleCompleted}
     * after their own idempotent command has been accepted.
     */
    public CompletionResult completeWithoutClaim(
            Review review, ReviewCommandMetadata metadata, RoleType actorRole, String publicSummary) {
        synchronized (review) {
            Objects.requireNonNull(review, "review must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");
            requireText(publicSummary, "publicSummary");
            if (!review.id().equals(metadata.reviewId())) {
                throw new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                        "completion command reviewId does not match aggregate");
            }
            String existing = review.commandResults().get(metadata.idempotencyKey());
            if (existing != null) {
                return new CompletionResult(true, review.stage());
            }
            requireActiveInitialReviewer(review, actorRole);
            review.recordCommand(metadata, "initial-review-complete:" + actorRole.name());
            boolean newlyCompleted = !isCompleted(review, actorRole);
            review.completeInitialReview(actorRole);
            return afterRoleCompleted(review, actorRole, newlyCompleted, publicSummary);
        }
    }

    /**
     * Emits role and stage facts after a successful Claim command or no-finding completion.
     */
    private CompletionResult afterRoleCompleted(
            Review review, RoleType actorRole, boolean newlyCompleted, String publicSummary) {
        Objects.requireNonNull(review, "review must not be null");
        requireActiveInitialReviewer(review, actorRole);
        if (newlyCompleted) {
            eventPublisher.publish(ReviewEventDrafts.completedCommand(
                    review, ReviewEventType.ROLE_COMPLETED, actorRole, null, null, null, null,
                    null, 40, Map.of("summary", requireText(publicSummary, "publicSummary"))));
        }
        if (review.stage() == ReviewStage.INITIAL_REVIEW
                && protocolGuard.validateDebateStart(review.roleActivations()).isValid()) {
            review.transitionTo(stateMachine, ReviewStage.CONFLICT_DETECTION);
            eventPublisher.publish(ReviewEventDrafts.completedCommand(
                    review, ReviewEventType.INITIAL_REVIEW_COMPLETED, RoleType.DIRECTOR, null,
                    null, null, null, null, 50, Map.of("completedBy", actorRole.name())));
        }
        return new CompletionResult(!newlyCompleted, review.stage());
    }

    private void requireActiveInitialReviewer(Review review, RoleType actorRole) {
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        if (review.stage() != ReviewStage.INITIAL_REVIEW || actorRole == RoleType.DIRECTOR || actorRole == RoleType.JUDGE
                || review.roleActivations().stream().noneMatch(activation -> activation.roleType() == actorRole)) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "only an active review role can complete initial review");
        }
    }

    private boolean isCompleted(Review review, RoleType roleType) {
        return review.roleActivations().stream()
                .filter(activation -> activation.roleType() == roleType)
                .findFirst()
                .orElseThrow()
                .initialReviewCompleted();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Tool completion result returned to the AgentScope adapter.
     *
     * @author wangli
     */
    public static final class CompletionResult {

        private final boolean replayed;
        private final ReviewStage stage;

        public CompletionResult(boolean replayed, ReviewStage stage) {
            this.replayed = replayed;
            this.stage = Objects.requireNonNull(stage, "stage must not be null");
        }

        public boolean replayed() {
            return replayed;
        }

        public ReviewStage stage() {
            return stage;
        }
    }
}
