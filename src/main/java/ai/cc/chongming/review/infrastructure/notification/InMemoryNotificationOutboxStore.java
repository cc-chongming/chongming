package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.domain.model.NotificationOutboxEntry;
import ai.cc.chongming.review.domain.model.NotificationOutboxEntry.DeliveryStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.NotificationOutboxStore;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [AIREVIEW-PLAN-011#1.5] Process-local atomic claim store used before the MyBatis Outbox writer is enabled.
 *
 * @author wangli
 */
@Repository
public class InMemoryNotificationOutboxStore implements NotificationOutboxStore {

    private final Map<UUID, NotificationOutboxEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, UUID> notificationByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public synchronized NotificationOutboxEntry enqueue(NotificationOutboxEntry entry) {
        UUID existingId = notificationByIdempotencyKey.putIfAbsent(entry.command().idempotencyKey(), entry.notificationId());
        if (existingId != null) {
            return entries.get(existingId);
        }
        entries.put(entry.notificationId(), entry);
        return entry;
    }

    @Override
    public synchronized void save(NotificationOutboxEntry entry) {
        NotificationOutboxEntry current = entries.get(entry.notificationId());
        if (current == null) {
            throw new java.util.NoSuchElementException("notification does not exist");
        }
        if (current.version() >= entry.version()) {
            throw new IllegalStateException("notification version is stale");
        }
        entries.put(entry.notificationId(), entry);
    }

    @Override
    public Optional<NotificationOutboxEntry> find(UUID notificationId) {
        return Optional.ofNullable(entries.get(notificationId));
    }

    @Override
    public Optional<NotificationOutboxEntry> findByIdempotencyKey(String idempotencyKey) {
        UUID notificationId = notificationByIdempotencyKey.get(idempotencyKey);
        return notificationId == null ? Optional.empty() : find(notificationId);
    }

    @Override
    public List<NotificationOutboxEntry> findByReview(ReviewId reviewId) {
        return entries.values().stream()
                .filter(entry -> entry.command().reviewId().equals(reviewId))
                .sorted(Comparator.comparing(NotificationOutboxEntry::createdAt)
                        .thenComparing(NotificationOutboxEntry::notificationId))
                .toList();
    }

    @Override
    public synchronized List<NotificationOutboxEntry> claimDue(Instant now, int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("claim limit must be between 1 and 500");
        }
        List<NotificationOutboxEntry> candidates = entries.values().stream()
                .filter(entry -> (entry.deliveryStatus() == DeliveryStatus.PENDING
                        || entry.deliveryStatus() == DeliveryStatus.FAILED)
                        && !entry.nextRetryAt().isAfter(now))
                .sorted(Comparator.comparing(NotificationOutboxEntry::nextRetryAt)
                        .thenComparing(NotificationOutboxEntry::notificationId))
                .limit(limit)
                .toList();
        List<NotificationOutboxEntry> claimed = new ArrayList<>(candidates.size());
        for (NotificationOutboxEntry candidate : candidates) {
            NotificationOutboxEntry sending = candidate.claim(candidate.version(), now);
            entries.put(sending.notificationId(), sending);
            claimed.add(sending);
        }
        return List.copyOf(claimed);
    }
}
