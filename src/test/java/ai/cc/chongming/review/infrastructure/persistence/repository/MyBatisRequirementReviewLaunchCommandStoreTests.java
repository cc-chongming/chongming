package ai.cc.chongming.review.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore.ReservationStatus;
import ai.cc.chongming.review.infrastructure.persistence.mapper.RequirementMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-023#3] Verifies durable launch reservation, conflict, replay and takeover mapping.
 *
 * @author zyj
 */
class MyBatisRequirementReviewLaunchCommandStoreTests {

    private final RequirementId requirementId = new RequirementId(UUID.randomUUID());
    private final UUID owner = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-10T08:00:00Z");

    @Test
    void acquiresANewPersistentReservation() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        when(mapper.insertLaunchCommand(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        var reservation = store(mapper).reserve(requirementId, "launch-1", "a".repeat(64), owner);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.ACQUIRED);
    }

    @Test
    void returnsReplayForTheSameFingerprintAfterRestart() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        when(mapper.findLaunchCommand(requirementId.value().toString(), "launch-1")).thenReturn(
                row("a".repeat(64), owner.toString(), now.plusSeconds(30), reviewId.value().toString()));

        var reservation = store(mapper).reserve(requirementId, "launch-1", "a".repeat(64), UUID.randomUUID());

        assertThat(reservation.status()).isEqualTo(ReservationStatus.REPLAY);
        assertThat(reservation.reviewId()).isEqualTo(reviewId);
    }

    @Test
    void rejectsReuseOfTheSameKeyWithAnotherFingerprint() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        when(mapper.findLaunchCommand(requirementId.value().toString(), "launch-1")).thenReturn(
                row("a".repeat(64), owner.toString(), now.plusSeconds(30), null));

        assertThat(store(mapper).reserve(requirementId, "launch-1", "b".repeat(64), UUID.randomUUID()).status())
                .isEqualTo(ReservationStatus.CONFLICT);
    }

    @Test
    void atomicallyTakesOverAnExpiredReservation() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        UUID nextOwner = UUID.randomUUID();
        when(mapper.findLaunchCommand(requirementId.value().toString(), "launch-1")).thenReturn(
                row("a".repeat(64), owner.toString(), now.minusSeconds(1), null));
        when(mapper.takeOverExpiredLaunchCommand(
                requirementId.value().toString(),
                "launch-1",
                "a".repeat(64),
                nextOwner.toString(),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.plusSeconds(30), ZoneOffset.UTC)))
                .thenReturn(1);

        assertThat(store(mapper).reserve(requirementId, "launch-1", "a".repeat(64), nextOwner).status())
                .isEqualTo(ReservationStatus.ACQUIRED);
    }

    @Test
    void completesOnlyTheActiveOwnerReservation() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        when(mapper.completeLaunchCommand(
                requirementId.value().toString(),
                "launch-1",
                "a".repeat(64),
                owner.toString(),
                reviewId.value().toString()))
                .thenReturn(1);

        assertThat(store(mapper).complete(requirementId, "launch-1", "a".repeat(64), owner, reviewId)).isTrue();
        verify(mapper).completeLaunchCommand(
                requirementId.value().toString(),
                "launch-1",
                "a".repeat(64),
                owner.toString(),
                reviewId.value().toString());
    }

    @Test
    void renewsOnlyTheActiveOwnerReservation() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        when(mapper.renewLaunchCommand(
                requirementId.value().toString(),
                "launch-1",
                owner.toString(),
                LocalDateTime.ofInstant(now.plusSeconds(30), ZoneOffset.UTC)))
                .thenReturn(1);

        assertThat(store(mapper).renew(requirementId, "launch-1", owner)).isTrue();
        verify(mapper).renewLaunchCommand(
                requirementId.value().toString(),
                "launch-1",
                owner.toString(),
                LocalDateTime.ofInstant(now.plusSeconds(30), ZoneOffset.UTC));
    }

    private MyBatisRequirementReviewLaunchCommandStore store(RequirementMapper mapper) {
        return new MyBatisRequirementReviewLaunchCommandStore(
                mapper, Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(30));
    }

    private RequirementMapper.RequirementReviewLaunchCommandRow row(
            String fingerprint, String ownerToken, Instant leaseUntil, String reviewId) {
        return new RequirementMapper.RequirementReviewLaunchCommandRow(
                requirementId.value().toString(),
                "launch-1",
                fingerprint,
                ownerToken,
                LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC),
                reviewId);
    }
}
