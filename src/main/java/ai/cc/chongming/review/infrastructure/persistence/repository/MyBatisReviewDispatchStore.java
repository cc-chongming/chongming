package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchCommandStatus;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import ai.cc.chongming.review.domain.repository.ReviewDispatchStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewDispatchPersistenceMapper;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewDispatchPersistenceMapper.DispatchCommandRow;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-024#方案5] Durable dispatch command store used whenever review persistence is
 * enabled. Mirrors {@code InMemoryReviewDispatchStore} semantics: issuance is idempotent on the
 * idempotency key (the first persisted command wins), commandId collisions with different content
 * are rejected, and status transitions replace the stored row. All queries are single batch reads.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewDispatchStore implements ReviewDispatchStore {

    private final ReviewDispatchPersistenceMapper mapper;

    public MyBatisReviewDispatchStore(ReviewDispatchPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public void save(ReviewDispatchCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.status() != DispatchCommandStatus.PENDING) {
            throw new IllegalArgumentException("only PENDING dispatch commands may be saved");
        }
        String reviewId = command.reviewId().value().toString();
        // Idempotent issuance: the first persisted command for an idempotencyKey wins.
        if (mapper.findByIdempotencyKey(reviewId, command.idempotencyKey().value()) != null) {
            return;
        }
        DispatchCommandRow existingById = mapper.findById(reviewId, command.commandId().value().toString());
        if (existingById != null) {
            if (!existingById.equals(toRow(command))) {
                throw new IllegalStateException(
                        "dispatch commandId already exists: " + command.commandId().value());
            }
            return;
        }
        mapper.insertIgnore(toRow(command));
    }

    @Override
    @Transactional
    public void update(ReviewDispatchCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        int updated = mapper.updateStatus(toRow(command));
        if (updated != 1) {
            throw new IllegalStateException("dispatch command does not exist: " + command.commandId().value());
        }
    }

    @Override
    @Transactional
    public void updateExpiry(ReviewDispatchCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.status() != DispatchCommandStatus.PENDING) {
            throw new IllegalArgumentException("only PENDING dispatch commands may have their expiry refreshed");
        }
        int updated = mapper.updateExpiry(toRow(command));
        if (updated != 1) {
            throw new IllegalStateException("pending dispatch command does not exist: " + command.commandId().value());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewDispatchCommand> findById(ReviewId reviewId, CommandId commandId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        return Optional.ofNullable(
                        mapper.findById(reviewId.value().toString(), commandId.value().toString()))
                .map(this::toCommand);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewDispatchCommand> findByIdempotencyKey(ReviewId reviewId, IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        return Optional.ofNullable(
                        mapper.findByIdempotencyKey(reviewId.value().toString(), idempotencyKey.value()))
                .map(this::toCommand);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDispatchCommand> findByReview(ReviewId reviewId, int attemptNo) {
        requireAttempt(reviewId, attemptNo);
        return mapper.findByAttempt(reviewId.value().toString(), attemptNo).stream()
                .map(this::toCommand)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDispatchCommand> findPendingByRecipient(ReviewId reviewId, int attemptNo, RoleType recipientRole) {
        requireAttempt(reviewId, attemptNo);
        Objects.requireNonNull(recipientRole, "recipientRole must not be null");
        return mapper.findPendingByAttemptAndRecipient(
                        reviewId.value().toString(), attemptNo, recipientRole.name()).stream()
                .map(this::toCommand)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDispatchCommand> findPending(ReviewId reviewId, int attemptNo) {
        requireAttempt(reviewId, attemptNo);
        return mapper.findPendingByAttempt(reviewId.value().toString(), attemptNo).stream()
                .map(this::toCommand)
                .toList();
    }

    private void requireAttempt(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }

    private DispatchCommandRow toRow(ReviewDispatchCommand command) {
        return new DispatchCommandRow(
                command.commandId().value().toString(),
                command.reviewId().value().toString(),
                command.attemptNo(),
                command.stage().name(),
                command.round(),
                command.recipientRole().name(),
                command.allowedAction().name(),
                command.topicId() == null ? null : command.topicId().value().toString(),
                command.targetClaimId() == null ? null : command.targetClaimId().value().toString(),
                command.targetTurnId() == null ? null : command.targetTurnId().value().toString(),
                command.expiresAt().atOffset(ZoneOffset.UTC).toLocalDateTime(),
                command.status().name(),
                command.idempotencyKey().value(),
                command.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    private ReviewDispatchCommand toCommand(DispatchCommandRow row) {
        return new ReviewDispatchCommand(
                new CommandId(UUID.fromString(row.commandId())),
                new ReviewId(UUID.fromString(row.reviewId())),
                row.attemptNo(),
                ReviewStage.valueOf(row.stage()),
                row.round(),
                RoleType.valueOf(row.recipientRole()),
                DispatchedAction.valueOf(row.allowedAction()),
                row.topicId() == null ? null : new TopicId(UUID.fromString(row.topicId())),
                row.targetClaimId() == null ? null : new ClaimId(UUID.fromString(row.targetClaimId())),
                row.targetTurnId() == null ? null : new TurnId(UUID.fromString(row.targetTurnId())),
                row.expiresAt().toInstant(ZoneOffset.UTC),
                DispatchCommandStatus.valueOf(row.status()),
                new IdempotencyKey(row.idempotencyKey()),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
