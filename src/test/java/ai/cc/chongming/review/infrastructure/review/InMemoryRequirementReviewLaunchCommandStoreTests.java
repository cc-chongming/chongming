package ai.cc.chongming.review.infrastructure.review;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementReviewLaunchCommandStore.ReservationStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-023#3] Verifies atomic in-memory launch reservations and replay state.
 *
 * @author zyj
 */
class InMemoryRequirementReviewLaunchCommandStoreTests {

    private final RequirementId requirementId = new RequirementId(UUID.randomUUID());

    @Test
    void allowsOnlyTheFirstOwnerAndRejectsAChangedRequestFingerprint() {
        InMemoryRequirementReviewLaunchCommandStore store = new InMemoryRequirementReviewLaunchCommandStore();
        UUID firstOwner = UUID.randomUUID();

        assertThat(store.reserve(requirementId, "launch-1", "a".repeat(64), firstOwner).status())
                .isEqualTo(ReservationStatus.ACQUIRED);
        assertThat(store.reserve(requirementId, "launch-1", "a".repeat(64), UUID.randomUUID()).status())
                .isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(store.reserve(requirementId, "launch-1", "b".repeat(64), UUID.randomUUID()).status())
                .isEqualTo(ReservationStatus.CONFLICT);
    }

    @Test
    void replaysThePersistedReviewAcrossServiceReconstruction() {
        InMemoryRequirementReviewLaunchCommandStore store = new InMemoryRequirementReviewLaunchCommandStore();
        UUID owner = UUID.randomUUID();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        store.reserve(requirementId, "launch-1", "a".repeat(64), owner);

        assertThat(store.complete(requirementId, "launch-1", "a".repeat(64), owner, reviewId)).isTrue();
        assertThat(store.reserve(requirementId, "launch-1", "a".repeat(64), UUID.randomUUID()))
                .satisfies(reservation -> {
                    assertThat(reservation.status()).isEqualTo(ReservationStatus.REPLAY);
                    assertThat(reservation.reviewId()).isEqualTo(reviewId);
                });
    }

    @Test
    void letsANewOwnerRecoverAnExpiredReservation() {
        Instant now = Instant.parse("2026-08-10T08:00:00Z");
        MutableClock clock = new MutableClock(now);
        InMemoryRequirementReviewLaunchCommandStore store =
                new InMemoryRequirementReviewLaunchCommandStore(clock, Duration.ofSeconds(30));
        store.reserve(requirementId, "launch-1", "a".repeat(64), UUID.randomUUID());
        clock.set(now.plusSeconds(31));

        assertThat(store.reserve(requirementId, "launch-1", "a".repeat(64), UUID.randomUUID()).status())
                .isEqualTo(ReservationStatus.ACQUIRED);
    }

    @Test
    void renewalKeepsTheActiveOwnerLeaseFromBeingTakenOver() {
        Instant now = Instant.parse("2026-08-10T08:00:00Z");
        MutableClock clock = new MutableClock(now);
        InMemoryRequirementReviewLaunchCommandStore store =
                new InMemoryRequirementReviewLaunchCommandStore(clock, Duration.ofSeconds(30));
        UUID owner = UUID.randomUUID();
        store.reserve(requirementId, "launch-1", "a".repeat(64), owner);
        clock.set(now.plusSeconds(20));

        assertThat(store.renew(requirementId, "launch-1", owner)).isTrue();
        clock.set(now.plusSeconds(31));
        assertThat(store.reserve(requirementId, "launch-1", "a".repeat(64), UUID.randomUUID()).status())
                .isEqualTo(ReservationStatus.IN_PROGRESS);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
