package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.NotificationOutboxEntry;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.5] Persistence boundary for idempotent notification delivery state.
 *
 * @author wangli
 */
public interface NotificationOutboxStore {

    NotificationOutboxEntry enqueue(NotificationOutboxEntry entry);

    void save(NotificationOutboxEntry entry);

    Optional<NotificationOutboxEntry> find(UUID notificationId);

    Optional<NotificationOutboxEntry> findByIdempotencyKey(String idempotencyKey);

    List<NotificationOutboxEntry> findByReview(ReviewId reviewId);

    List<NotificationOutboxEntry> claimDue(Instant now, int limit);
}
