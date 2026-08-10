package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.RequirementMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-023#3] MyBatis-backed launch idempotency reservation with lease-based crash recovery.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisRequirementReviewLaunchCommandStore implements RequirementReviewLaunchCommandStore {

    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);

    private final RequirementMapper mapper;
    private final Clock clock;
    private final Duration leaseDuration;

    @Autowired
    public MyBatisRequirementReviewLaunchCommandStore(RequirementMapper mapper) {
        this(mapper, Clock.systemUTC(), DEFAULT_LEASE);
    }

    public MyBatisRequirementReviewLaunchCommandStore(
            RequirementMapper mapper, Clock clock, Duration leaseDuration) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
    }

    @Override
    @Transactional
    public Reservation reserve(
            RequirementId requirementId, String idempotencyKey, String requestFingerprint, UUID ownerToken) {
        String requirementValue = requirementId.value().toString();
        String ownerValue = ownerToken.toString();
        LocalDateTime now = utc(clock.instant());
        LocalDateTime leaseUntil = utc(clock.instant().plus(leaseDuration));
        RequirementMapper.RequirementReviewLaunchCommandRow inserted =
                new RequirementMapper.RequirementReviewLaunchCommandRow(
                        requirementValue,
                        idempotencyKey,
                        requestFingerprint,
                        ownerValue,
                        leaseUntil,
                        null);
        if (mapper.insertLaunchCommand(inserted) == 1) {
            return Reservation.acquired();
        }
        RequirementMapper.RequirementReviewLaunchCommandRow existing =
                mapper.findLaunchCommand(requirementValue, idempotencyKey);
        if (existing == null) {
            return Reservation.inProgress();
        }
        if (!existing.requestFingerprint().equals(requestFingerprint)) {
            return Reservation.conflict();
        }
        if (existing.reviewId() != null) {
            return Reservation.replay(new ReviewId(UUID.fromString(existing.reviewId())));
        }
        if (!existing.leaseUntil().isAfter(now)
                && mapper.takeOverExpiredLaunchCommand(
                requirementValue,
                idempotencyKey,
                requestFingerprint,
                ownerValue,
                now,
                leaseUntil)
                == 1) {
            return Reservation.acquired();
        }
        RequirementMapper.RequirementReviewLaunchCommandRow latest =
                mapper.findLaunchCommand(requirementValue, idempotencyKey);
        if (latest != null && latest.reviewId() != null && latest.requestFingerprint().equals(requestFingerprint)) {
            return Reservation.replay(new ReviewId(UUID.fromString(latest.reviewId())));
        }
        return Reservation.inProgress();
    }

    @Override
    @Transactional
    public boolean complete(
            RequirementId requirementId,
            String idempotencyKey,
            String requestFingerprint,
            UUID ownerToken,
            ReviewId reviewId) {
        String requirementValue = requirementId.value().toString();
        String reviewValue = reviewId.value().toString();
        if (mapper.completeLaunchCommand(
                requirementValue,
                idempotencyKey,
                requestFingerprint,
                ownerToken.toString(),
                reviewValue)
                == 1) {
            return true;
        }
        RequirementMapper.RequirementReviewLaunchCommandRow existing =
                mapper.findLaunchCommand(requirementValue, idempotencyKey);
        return existing != null
                && requestFingerprint.equals(existing.requestFingerprint())
                && reviewValue.equals(existing.reviewId());
    }

    @Override
    @Transactional
    public boolean renew(RequirementId requirementId, String idempotencyKey, UUID ownerToken) {
        return mapper.renewLaunchCommand(
                requirementId.value().toString(),
                idempotencyKey,
                ownerToken.toString(),
                utc(clock.instant().plus(leaseDuration)))
                == 1;
    }

    @Override
    @Transactional
    public void release(RequirementId requirementId, String idempotencyKey, UUID ownerToken) {
        mapper.releaseLaunchCommand(
                requirementId.value().toString(), idempotencyKey, ownerToken.toString());
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
