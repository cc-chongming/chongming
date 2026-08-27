package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchCommandStatus;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewDispatchPersistenceMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-024#方案5] Verifies the durable dispatch command store keeps the
 * {@code InMemoryReviewDispatchStore} semantics: issuance is idempotent on the idempotency key,
 * commandId collisions with different content are rejected, status transitions replace the stored
 * row, and every read is a single batch query ordered by creation.
 *
 * @author wangli
 */
class MyBatisReviewDispatchStoreTests {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @Test
    void roundTripsPendingCommandWithOptionalTargets() {
        MyBatisReviewDispatchStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        TopicId topicId = new TopicId(UUID.randomUUID());
        ReviewDispatchCommand command = command(reviewId, 1, RoleType.BACKEND, DispatchedAction.REBUTTAL,
                topicId, "dispatch:rebuttal:t-1", NOW);

        store.save(command);

        ReviewDispatchCommand reloaded = store.findById(reviewId, command.commandId()).orElseThrow();
        assertThat(reloaded.reviewId()).isEqualTo(reviewId);
        assertThat(reloaded.attemptNo()).isEqualTo(1);
        assertThat(reloaded.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);
        assertThat(reloaded.round()).isEqualTo(1);
        assertThat(reloaded.recipientRole()).isEqualTo(RoleType.BACKEND);
        assertThat(reloaded.allowedAction()).isEqualTo(DispatchedAction.REBUTTAL);
        assertThat(reloaded.topicId()).isEqualTo(topicId);
        assertThat(reloaded.targetClaimId()).isNull();
        assertThat(reloaded.targetTurnId()).isNull();
        assertThat(reloaded.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(reloaded.status()).isEqualTo(DispatchCommandStatus.PENDING);
        assertThat(reloaded.idempotencyKey().value()).isEqualTo("dispatch:rebuttal:t-1");
        assertThat(reloaded.createdAt()).isEqualTo(NOW);
        assertThat(store.findByIdempotencyKey(reviewId, new IdempotencyKey("dispatch:rebuttal:t-1")))
                .contains(reloaded);
        assertThat(store.hasStatus(reviewId, command.commandId(), DispatchCommandStatus.PENDING)).isTrue();
    }

    @Test
    void roundTripsDefenseDispatchAction() {
        MyBatisReviewDispatchStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        TopicId topicId = new TopicId(UUID.randomUUID());
        ReviewDispatchCommand command = new ReviewDispatchCommand(
                new CommandId(UUID.randomUUID()), reviewId, 1, ReviewStage.DEBATE_ROUND_1, 1,
                RoleType.PRODUCT, DispatchedAction.DEFENSE, topicId, null, null,
                NOW.plusSeconds(600), DispatchCommandStatus.PENDING,
                new IdempotencyKey("dispatch:defense:t-1"), NOW);

        store.save(command);

        ReviewDispatchCommand reloaded = store.findById(reviewId, command.commandId()).orElseThrow();
        assertThat(reloaded.allowedAction()).isEqualTo(DispatchedAction.DEFENSE);
        assertThat(reloaded.topicId()).isEqualTo(topicId);
        assertThat(reloaded.targetClaimId()).isNull();
        assertThat(reloaded.targetTurnId()).isNull();
    }

    @Test
    void repeatedIssuanceWithTheSameIdempotencyKeyKeepsTheFirstCommand() {
        MyBatisReviewDispatchStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewDispatchCommand first = command(reviewId, 1, RoleType.PRODUCT, DispatchedAction.CHALLENGE,
                null, "dispatch:challenge:round-1", NOW);
        store.save(first);
        ReviewDispatchCommand duplicate = command(reviewId, 1, RoleType.FRONTEND, DispatchedAction.REBUTTAL,
                null, "dispatch:challenge:round-1", NOW);

        store.save(duplicate);

        assertThat(store.findByReview(reviewId, 1)).singleElement()
                .extracting(ReviewDispatchCommand::commandId)
                .isEqualTo(first.commandId());
    }

    @Test
    void rejectsCommandIdCollisionWithDifferentContent() {
        MyBatisReviewDispatchStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewDispatchCommand first = command(reviewId, 1, RoleType.PRODUCT, DispatchedAction.CHALLENGE,
                null, "dispatch:challenge:a", NOW);
        store.save(first);
        ReviewDispatchCommand collision = new ReviewDispatchCommand(
                first.commandId(), reviewId, 1, ReviewStage.DEBATE_ROUND_1, 1, RoleType.BACKEND,
                DispatchedAction.REBUTTAL, null, null, null, NOW.plusSeconds(600),
                DispatchCommandStatus.PENDING, new IdempotencyKey("dispatch:challenge:b"), NOW);

        assertThatThrownBy(() -> store.save(collision))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commandId already exists");
        assertThatThrownBy(() -> store.save(first.withStatus(DispatchCommandStatus.CONSUMED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only PENDING");
    }

    @Test
    void updateReplacesStoredStatusAndRejectsUnknownCommands() {
        MyBatisReviewDispatchStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewDispatchCommand command = command(reviewId, 1, RoleType.PRODUCT, DispatchedAction.CHALLENGE,
                null, "dispatch:challenge:consume", NOW);
        store.save(command);

        store.update(command.withStatus(DispatchCommandStatus.CONSUMED));

        assertThat(store.findById(reviewId, command.commandId()).orElseThrow().status())
                .isEqualTo(DispatchCommandStatus.CONSUMED);
        assertThat(store.hasStatus(reviewId, command.commandId(), DispatchCommandStatus.PENDING)).isFalse();
        ReviewDispatchCommand unknown = command(reviewId, 1, RoleType.FRONTEND, DispatchedAction.REBUTTAL,
                null, "dispatch:unknown", NOW);
        assertThatThrownBy(() -> store.update(unknown.withStatus(DispatchCommandStatus.CONSUMED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void findsAttemptBatchOrderedByCreationAndFiltersPendingCommands() {
        MyBatisReviewDispatchStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewDispatchCommand consumed = command(reviewId, 1, RoleType.PRODUCT, DispatchedAction.CHALLENGE,
                null, "dispatch:1", NOW);
        ReviewDispatchCommand pendingBackend = command(reviewId, 1, RoleType.BACKEND, DispatchedAction.REBUTTAL,
                null, "dispatch:2", NOW.plusSeconds(30));
        ReviewDispatchCommand pendingFrontend = command(reviewId, 1, RoleType.FRONTEND,
                DispatchedAction.EVIDENCE_REQUEST, null, "dispatch:3", NOW.plusSeconds(60));
        store.save(consumed);
        store.save(pendingBackend);
        store.save(pendingFrontend);
        store.update(consumed.withStatus(DispatchCommandStatus.CONSUMED));
        store.save(command(reviewId, 2, RoleType.PROJECT, DispatchedAction.POSITION_CHANGE,
                null, "dispatch:attempt-2", NOW));

        assertThat(store.findByReview(reviewId, 1))
                .extracting(command -> command.idempotencyKey().value())
                .containsExactly("dispatch:1", "dispatch:2", "dispatch:3");
        assertThat(store.findPending(reviewId, 1))
                .extracting(ReviewDispatchCommand::recipientRole)
                .containsExactly(RoleType.BACKEND, RoleType.FRONTEND);
        assertThat(store.findPendingByRecipient(reviewId, 1, RoleType.BACKEND))
                .singleElement()
                .extracting(ReviewDispatchCommand::allowedAction)
                .isEqualTo(DispatchedAction.REBUTTAL);
        assertThat(store.findPendingByRecipient(reviewId, 1, RoleType.PRODUCT)).isEmpty();
        assertThat(store.findPending(reviewId, 2))
                .singleElement()
                .extracting(command -> command.idempotencyKey().value())
                .isEqualTo("dispatch:attempt-2");
    }

    @Test
    void updateExpiryRefreshesStoredExpiryAndRejectsUnknownOrNonPendingCommands() {
        MyBatisReviewDispatchStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewDispatchCommand command = command(reviewId, 1, RoleType.PRODUCT, DispatchedAction.DEFENSE,
                null, "dispatch:defense:expiry", NOW);
        store.save(command);

        store.updateExpiry(command.withExpiresAt(NOW.plusSeconds(1800)));

        assertThat(store.findById(reviewId, command.commandId()).orElseThrow().expiresAt())
                .isEqualTo(NOW.plusSeconds(1800));
        ReviewDispatchCommand unknown = command(reviewId, 1, RoleType.FRONTEND, DispatchedAction.REBUTTAL,
                null, "dispatch:unknown-expiry", NOW);
        assertThatThrownBy(() -> store.updateExpiry(unknown.withExpiresAt(NOW.plusSeconds(1800))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
        ReviewDispatchCommand consumed = command.withStatus(DispatchCommandStatus.CONSUMED);
        assertThatThrownBy(() -> store.updateExpiry(consumed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only PENDING");
    }

    private MyBatisReviewDispatchStore newStore() {
        return new MyBatisReviewDispatchStore(new FakeDispatchPersistenceMapper());
    }

    private ReviewDispatchCommand command(
            ReviewId reviewId,
            int attemptNo,
            RoleType recipient,
            DispatchedAction action,
            TopicId topicId,
            String idempotencyKey,
            Instant createdAt) {
        return new ReviewDispatchCommand(new CommandId(UUID.randomUUID()), reviewId, attemptNo,
                ReviewStage.DEBATE_ROUND_1, 1, recipient, action, topicId, null, null,
                NOW.plusSeconds(600), DispatchCommandStatus.PENDING, new IdempotencyKey(idempotencyKey), createdAt);
    }

    /** @author wangli */
    private static final class FakeDispatchPersistenceMapper implements ReviewDispatchPersistenceMapper {

        private final Map<String, DispatchCommandRow> rowsById = new LinkedHashMap<>();
        private final Map<String, DispatchCommandRow> rowsByIdempotencyKey = new LinkedHashMap<>();

        @Override
        public int insertIgnore(DispatchCommandRow row) {
            if (rowsById.containsKey(row.commandId()) || rowsByIdempotencyKey.containsKey(row.idempotencyKey())) {
                return 0;
            }
            rowsById.put(row.commandId(), row);
            rowsByIdempotencyKey.put(row.idempotencyKey(), row);
            return 1;
        }

        @Override
        public int updateStatus(DispatchCommandRow row) {
            DispatchCommandRow existing = rowsById.get(row.commandId());
            if (existing == null || !existing.reviewId().equals(row.reviewId())) {
                return 0;
            }
            DispatchCommandRow updated = new DispatchCommandRow(existing.commandId(), existing.reviewId(),
                    existing.attemptNo(), existing.stage(), existing.round(), existing.recipientRole(),
                    existing.allowedAction(), existing.topicId(), existing.targetClaimId(),
                    existing.targetTurnId(), existing.expiresAt(), row.status(),
                    existing.idempotencyKey(), existing.createdAt());
            rowsById.put(row.commandId(), updated);
            rowsByIdempotencyKey.put(existing.idempotencyKey(), updated);
            return 1;
        }

        @Override
        public int updateExpiry(DispatchCommandRow row) {
            DispatchCommandRow existing = rowsById.get(row.commandId());
            if (existing == null || !existing.reviewId().equals(row.reviewId())
                    || !"PENDING".equals(existing.status())) {
                return 0;
            }
            DispatchCommandRow updated = new DispatchCommandRow(existing.commandId(), existing.reviewId(),
                    existing.attemptNo(), existing.stage(), existing.round(), existing.recipientRole(),
                    existing.allowedAction(), existing.topicId(), existing.targetClaimId(),
                    existing.targetTurnId(), row.expiresAt(), existing.status(),
                    existing.idempotencyKey(), existing.createdAt());
            rowsById.put(row.commandId(), updated);
            rowsByIdempotencyKey.put(existing.idempotencyKey(), updated);
            return 1;
        }

        @Override
        public DispatchCommandRow findById(String reviewId, String commandId) {
            DispatchCommandRow row = rowsById.get(commandId);
            return row != null && row.reviewId().equals(reviewId) ? row : null;
        }

        @Override
        public DispatchCommandRow findByIdempotencyKey(String reviewId, String idempotencyKey) {
            DispatchCommandRow row = rowsByIdempotencyKey.get(idempotencyKey);
            return row != null && row.reviewId().equals(reviewId) ? row : null;
        }

        @Override
        public List<DispatchCommandRow> findByAttempt(String reviewId, int attemptNo) {
            return rowsById.values().stream()
                    .filter(row -> row.reviewId().equals(reviewId) && row.attemptNo() == attemptNo)
                    .sorted(creationOrder())
                    .toList();
        }

        @Override
        public List<DispatchCommandRow> findPendingByAttempt(String reviewId, int attemptNo) {
            return findByAttempt(reviewId, attemptNo).stream()
                    .filter(row -> "PENDING".equals(row.status()))
                    .toList();
        }

        @Override
        public List<DispatchCommandRow> findPendingByAttemptAndRecipient(
                String reviewId, int attemptNo, String recipientRole) {
            return findPendingByAttempt(reviewId, attemptNo).stream()
                    .filter(row -> row.recipientRole().equals(recipientRole))
                    .toList();
        }

        private Comparator<DispatchCommandRow> creationOrder() {
            return Comparator.comparing(DispatchCommandRow::createdAt)
                    .thenComparing(DispatchCommandRow::commandId);
        }
    }
}
