package ai.cc.chongming.review.infrastructure.review;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * [AIREVIEW-PLAN-023#3] Process-local launch command store for default and demo profiles.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRequirementReviewLaunchCommandStore implements RequirementReviewLaunchCommandStore {

    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    private final ConcurrentMap<CommandKey, CommandState> commands = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration leaseDuration;

    public InMemoryRequirementReviewLaunchCommandStore() {
        this(Clock.systemUTC(), DEFAULT_LEASE);
    }

    public InMemoryRequirementReviewLaunchCommandStore(Clock clock, Duration leaseDuration) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    @Override
    public Reservation reserve(
            RequirementId requirementId, String idempotencyKey, String requestFingerprint, UUID ownerToken) {
        CommandKey key = new CommandKey(requirementId, idempotencyKey);
        String fingerprint = requireText(requestFingerprint, "requestFingerprint");
        UUID owner = Objects.requireNonNull(ownerToken, "ownerToken must not be null");
        Instant now = clock.instant();
        ReservationHolder holder = new ReservationHolder();
        commands.compute(key, (ignored, existing) -> {
            if (existing == null) {
                holder.reservation = Reservation.acquired();
                return new CommandState(fingerprint, owner, now.plus(leaseDuration), null);
            }
            if (!existing.requestFingerprint.equals(fingerprint)) {
                holder.reservation = Reservation.conflict();
                return existing;
            }
            if (existing.reviewId != null) {
                holder.reservation = Reservation.replay(existing.reviewId);
                return existing;
            }
            if (!existing.leaseUntil.isAfter(now)) {
                holder.reservation = Reservation.acquired();
                return new CommandState(fingerprint, owner, now.plus(leaseDuration), null);
            }
            holder.reservation = Reservation.inProgress();
            return existing;
        });
        return holder.reservation;
    }

    @Override
    public boolean complete(
            RequirementId requirementId,
            String idempotencyKey,
            String requestFingerprint,
            UUID ownerToken,
            ReviewId reviewId) {
        CommandKey key = new CommandKey(requirementId, idempotencyKey);
        ReviewId targetReviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        boolean[] completed = {false};
        commands.computeIfPresent(key, (ignored, existing) -> {
            if (existing.requestFingerprint.equals(requestFingerprint)
                    && existing.ownerToken.equals(ownerToken)
                    && existing.reviewId == null) {
                completed[0] = true;
                return new CommandState(existing.requestFingerprint, existing.ownerToken, existing.leaseUntil, targetReviewId);
            }
            if (targetReviewId.equals(existing.reviewId) && existing.requestFingerprint.equals(requestFingerprint)) {
                completed[0] = true;
            }
            return existing;
        });
        return completed[0];
    }

    @Override
    public boolean renew(RequirementId requirementId, String idempotencyKey, UUID ownerToken) {
        CommandKey key = new CommandKey(requirementId, idempotencyKey);
        UUID owner = Objects.requireNonNull(ownerToken, "ownerToken must not be null");
        Instant now = clock.instant();
        boolean[] renewed = {false};
        commands.computeIfPresent(key, (ignored, existing) -> {
            if (existing.ownerToken.equals(owner) && existing.reviewId == null) {
                renewed[0] = true;
                return new CommandState(
                        existing.requestFingerprint, existing.ownerToken, now.plus(leaseDuration), null);
            }
            return existing;
        });
        return renewed[0];
    }

    @Override
    public void release(RequirementId requirementId, String idempotencyKey, UUID ownerToken) {
        CommandKey key = new CommandKey(requirementId, idempotencyKey);
        commands.computeIfPresent(key, (ignored, existing) ->
                existing.ownerToken.equals(ownerToken) && existing.reviewId == null ? null : existing);
    }

    @Override
    public boolean invalidateCompleted(
            RequirementId requirementId,
            String idempotencyKey,
            String requestFingerprint,
            ReviewId reviewId) {
        CommandKey key = new CommandKey(requirementId, idempotencyKey);
        String fingerprint = requireText(requestFingerprint, "requestFingerprint");
        ReviewId targetReviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        boolean[] invalidated = {false};
        commands.computeIfPresent(key, (ignored, existing) -> {
            if (fingerprint.equals(existing.requestFingerprint) && targetReviewId.equals(existing.reviewId)) {
                invalidated[0] = true;
                return null;
            }
            return existing;
        });
        return invalidated[0];
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private record CommandKey(RequirementId requirementId, String idempotencyKey) {

        private CommandKey {
            Objects.requireNonNull(requirementId, "requirementId must not be null");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        }
    }

    private record CommandState(String requestFingerprint, UUID ownerToken, Instant leaseUntil, ReviewId reviewId) {
    }

    private static final class ReservationHolder {

        private Reservation reservation;
    }
}
