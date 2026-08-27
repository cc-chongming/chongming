package ai.cc.chongming.review.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-024#方案3] Immutable directed-dispatch envelope produced by the Director (or the
 * server after a committed challenge) and consumed by exactly one recipient role. It replaces the
 * former broadcast debate prompts: a role may only perform the single write action this command
 * allows, against the targets it names, before {@code expiresAt}.
 *
 * <p>Command lifecycle: {@code PENDING -> CONSUMED | EXPIRED | REJECTED}. Records are immutable;
 * every status transition yields a new instance via {@link #withStatus(DispatchCommandStatus)}.
 *
 * @author wangli
 */
public record ReviewDispatchCommand(
        CommandId commandId,
        ReviewId reviewId,
        int attemptNo,
        ReviewStage stage,
        int round,
        RoleType recipientRole,
        DispatchedAction allowedAction,
        TopicId topicId,
        ClaimId targetClaimId,
        TurnId targetTurnId,
        Instant expiresAt,
        DispatchCommandStatus status,
        IdempotencyKey idempotencyKey,
        Instant createdAt) {

    public ReviewDispatchCommand {
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(stage, "stage must not be null");
        if (round < 1 || round > 2) {
            throw new IllegalArgumentException("round must be between 1 and 2");
        }
        Objects.requireNonNull(recipientRole, "recipientRole must not be null");
        if (recipientRole == RoleType.DIRECTOR || recipientRole == RoleType.JUDGE) {
            throw new IllegalArgumentException("dispatch commands target review roles only");
        }
        Objects.requireNonNull(allowedAction, "allowedAction must not be null");
        // topicId, targetClaimId and targetTurnId are contract-optional; applicability is enforced
        // by ReviewDispatchService per action before persistence.
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Returns a copy of this command in the given terminal or consumed status. The source command
     * must be PENDING; the lifecycle is strictly {@code PENDING -> CONSUMED | EXPIRED | REJECTED}.
     */
    public ReviewDispatchCommand withStatus(DispatchCommandStatus nextStatus) {
        Objects.requireNonNull(nextStatus, "nextStatus must not be null");
        if (status != DispatchCommandStatus.PENDING) {
            throw new IllegalStateException(
                    "dispatch command " + commandId.value() + " is already " + status);
        }
        if (nextStatus == DispatchCommandStatus.PENDING) {
            throw new IllegalArgumentException("a dispatch command cannot return to PENDING");
        }
        return new ReviewDispatchCommand(commandId, reviewId, attemptNo, stage, round, recipientRole,
                allowedAction, topicId, targetClaimId, targetTurnId, expiresAt, nextStatus,
                idempotencyKey, createdAt);
    }

    /**
     * Returns a copy of this command with a refreshed expiry. The source command must be PENDING.
     * A re-dispatch restates live Director intent, so a still-wanted envelope must not silently
     * time out while queued.
     */
    public ReviewDispatchCommand withExpiresAt(Instant newExpiresAt) {
        Objects.requireNonNull(newExpiresAt, "newExpiresAt must not be null");
        if (status != DispatchCommandStatus.PENDING) {
            throw new IllegalStateException(
                    "dispatch command " + commandId.value() + " is already " + status);
        }
        return new ReviewDispatchCommand(commandId, reviewId, attemptNo, stage, round, recipientRole,
                allowedAction, topicId, targetClaimId, targetTurnId, newExpiresAt, status,
                idempotencyKey, createdAt);
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now must not be null"));
    }

    /**
     * Server-unforgeable identity of one dispatch envelope.
     *
     * @author wangli
     */
    public record CommandId(UUID value) {
        public CommandId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * The single write action a dispatched role may perform with the referenced commandId.
     *
     * @author wangli
     */
    public enum DispatchedAction {
        CHALLENGE,
        REBUTTAL,
        POSITION_CHANGE,
        EVIDENCE_REQUEST,
        /**
         * Authorizes the requirement defender (需求答辩人) to submit a SUPPORT claim on the
         * topic's subjectKey via {@code submit_claim} during a debate round.
         */
        DEFENSE
    }

    /**
     * Dispatch command lifecycle; PENDING commands move exactly once to a terminal status.
     *
     * @author wangli
     */
    public enum DispatchCommandStatus {
        PENDING,
        CONSUMED,
        EXPIRED,
        REJECTED
    }
}
