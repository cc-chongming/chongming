package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchCommandStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;

import java.util.List;
import java.util.Optional;

/**
 * [AIREVIEW-PLAN-024#方案3] Persistence boundary for directed dispatch commands. Commands are
 * idempotent on their idempotencyKey and unique on their commandId; status transitions are
 * applied by storing the updated immutable record.
 *
 * @author wangli
 */
public interface ReviewDispatchStore {

    /**
     * Persists one PENDING command. Saving the same idempotencyKey again is a no-op returning
     * without overwriting the previously persisted command (idempotent issuance).
     */
    void save(ReviewDispatchCommand command);

    /**
     * Applies a status transition by replacing the stored record; the command must already exist.
     */
    void update(ReviewDispatchCommand command);

    Optional<ReviewDispatchCommand> findById(ReviewId reviewId, CommandId commandId);

    Optional<ReviewDispatchCommand> findByIdempotencyKey(ReviewId reviewId, IdempotencyKey idempotencyKey);

    /**
     * Returns every command of one review attempt, ordered deterministically by creation.
     */
    List<ReviewDispatchCommand> findByReview(ReviewId reviewId, int attemptNo);

    /**
     * Returns the recipient's PENDING commands of one review attempt, ordered deterministically.
     */
    List<ReviewDispatchCommand> findPendingByRecipient(ReviewId reviewId, int attemptNo, RoleType recipientRole);

    /**
     * Returns every PENDING command of one review attempt, ordered deterministically.
     */
    List<ReviewDispatchCommand> findPending(ReviewId reviewId, int attemptNo);

    /**
     * True when the command exists in the given status.
     */
    default boolean hasStatus(ReviewId reviewId, CommandId commandId, DispatchCommandStatus status) {
        return findById(reviewId, commandId).map(command -> command.status() == status).orElse(false);
    }
}
