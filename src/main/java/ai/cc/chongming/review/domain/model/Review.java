package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-003#1.2,#1.3,#1.6][AIREVIEW-PLAN-010#1.7] Owns review lifecycle, attempt version and command replay state.
 *
 * @author wangli
 */
public final class Review {

    private final ReviewId id;
    private final List<RoleActivation> roleActivations = new ArrayList<>();
    private final Map<IdempotencyKey, String> commandResults = new LinkedHashMap<>();
    private ReviewStage stage;
    private int attemptNo;
    private long version;

    private Review(ReviewId id, ReviewStage stage, int attemptNo, long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
        this.attemptNo = attemptNo;
        this.version = version;
    }

    public static Review pending(ReviewId reviewId) {
        return new Review(reviewId, ReviewStage.PENDING, 1, 0L);
    }

    /**
     * Rehydrates an aggregate from its persistence snapshot without replaying domain commands.
     */
    public static Review restore(
            ReviewId reviewId,
            ReviewStage stage,
            int attemptNo,
            long version,
            List<RoleActivation> roleActivations,
            Map<IdempotencyKey, String> commandResults) {
        Review review = new Review(reviewId, stage, attemptNo, version);
        review.roleActivations.addAll(List.copyOf(roleActivations));
        review.commandResults.putAll(Map.copyOf(commandResults));
        return review;
    }

    public ReviewId id() {
        return id;
    }

    public ReviewStage stage() {
        return stage;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public long version() {
        return version;
    }

    public List<RoleActivation> roleActivations() {
        return List.copyOf(roleActivations);
    }

    public Map<IdempotencyKey, String> commandResults() {
        return Map.copyOf(commandResults);
    }

    public void transitionTo(ReviewStateMachine stateMachine, ReviewStage nextStage) {
        this.stage = stateMachine.transition(stage, nextStage);
        version++;
    }

    public void activateRole(RoleActivation activation) {
        roleActivations.add(Objects.requireNonNull(activation, "activation must not be null"));
        version++;
    }

    /**
     * Marks an activated role's independent initial review as complete without changing its immutable identity.
     */
    public void completeInitialReview(RoleType roleType) {
        Objects.requireNonNull(roleType, "roleType must not be null");
        for (int index = 0; index < roleActivations.size(); index++) {
            RoleActivation activation = roleActivations.get(index);
            if (activation.roleType() == roleType) {
                if (!activation.initialReviewCompleted()) {
                    roleActivations.set(index, new RoleActivation(
                            activation.roleType(), activation.agentLabel(), true));
                    version++;
                }
                return;
            }
        }
        throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                "only an activated role can complete an initial review");
    }

    public String recordCommand(ReviewCommandMetadata metadata, String resultReference) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        requireText(resultReference, "resultReference");
        if (!id.equals(metadata.reviewId())) {
            throw new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH, "command reviewId does not match aggregate");
        }

        String previousResult = commandResults.get(metadata.idempotencyKey());
        if (previousResult != null) {
            return previousResult;
        }
        if (metadata.expectedVersion() != version) {
            throw new ReviewDomainException(ReviewErrorCode.VERSION_CONFLICT, "expectedVersion does not match aggregate version");
        }

        commandResults.put(metadata.idempotencyKey(), resultReference);
        version++;
        return resultReference;
    }

    public void startNewAttempt() {
        if (!stage.isTerminal()) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "a new attempt requires a terminal review");
        }
        attemptNo++;
        stage = ReviewStage.PENDING;
        // The previous attempt remains in durable history; this aggregate now owns only the fresh runtime namespace.
        roleActivations.clear();
        commandResults.clear();
        version++;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

