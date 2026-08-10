package ai.cc.chongming.review.infrastructure.dispatch;

import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchCommandStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.ReviewDispatchStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-024#方案3] Process-local dispatch command store used while durable persistence
 * is disabled. Mirrors the conditional-wiring precedent of {@code InMemoryReviewAssessmentStore};
 * the MySQL counterpart takes over when {@code review.persistence.enabled=true}.
 *
 * @author wangli
 */
@Component
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryReviewDispatchStore implements ReviewDispatchStore {

    private static final Comparator<ReviewDispatchCommand> DETERMINISTIC_ORDER =
            Comparator.comparing(ReviewDispatchCommand::createdAt)
                    .thenComparing(command -> command.commandId().value().toString());

    private final Map<ReviewId, Map<CommandId, ReviewDispatchCommand>> commandsByReview = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(ReviewDispatchCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.status() != DispatchCommandStatus.PENDING) {
            throw new IllegalArgumentException("only PENDING dispatch commands may be saved");
        }
        Map<CommandId, ReviewDispatchCommand> stored =
                commandsByReview.computeIfAbsent(command.reviewId(), key -> new ConcurrentHashMap<>());
        ReviewDispatchCommand existingById = stored.get(command.commandId());
        if (existingById != null) {
            if (!existingById.equals(command)) {
                throw new IllegalStateException("dispatch commandId already exists: " + command.commandId().value());
            }
            return;
        }
        // Idempotent issuance: the first persisted command for an idempotencyKey wins.
        boolean duplicateKey = stored.values().stream()
                .anyMatch(existing -> existing.idempotencyKey().equals(command.idempotencyKey()));
        if (duplicateKey) {
            return;
        }
        stored.put(command.commandId(), command);
    }

    @Override
    public synchronized void update(ReviewDispatchCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Map<CommandId, ReviewDispatchCommand> stored = commandsByReview.get(command.reviewId());
        if (stored == null || !stored.containsKey(command.commandId())) {
            throw new IllegalStateException("dispatch command does not exist: " + command.commandId().value());
        }
        stored.put(command.commandId(), command);
    }

    @Override
    public Optional<ReviewDispatchCommand> findById(ReviewId reviewId, CommandId commandId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        return Optional.ofNullable(commandsByReview.getOrDefault(reviewId, Map.of()).get(commandId));
    }

    @Override
    public Optional<ReviewDispatchCommand> findByIdempotencyKey(ReviewId reviewId, IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        return commandsByReview.getOrDefault(reviewId, Map.of()).values().stream()
                .filter(command -> command.idempotencyKey().equals(idempotencyKey))
                .min(DETERMINISTIC_ORDER);
    }

    @Override
    public List<ReviewDispatchCommand> findByReview(ReviewId reviewId, int attemptNo) {
        requireAttempt(reviewId, attemptNo);
        return commandsByReview.getOrDefault(reviewId, Map.of()).values().stream()
                .filter(command -> command.attemptNo() == attemptNo)
                .sorted(DETERMINISTIC_ORDER)
                .toList();
    }

    @Override
    public List<ReviewDispatchCommand> findPendingByRecipient(ReviewId reviewId, int attemptNo, RoleType recipientRole) {
        Objects.requireNonNull(recipientRole, "recipientRole must not be null");
        return findPending(reviewId, attemptNo).stream()
                .filter(command -> command.recipientRole() == recipientRole)
                .toList();
    }

    @Override
    public List<ReviewDispatchCommand> findPending(ReviewId reviewId, int attemptNo) {
        return findByReview(reviewId, attemptNo).stream()
                .filter(command -> command.status() == DispatchCommandStatus.PENDING)
                .toList();
    }

    private void requireAttempt(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }
}
