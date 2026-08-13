package ai.cc.chongming.review.infrastructure.audit;

import ai.cc.chongming.review.domain.model.ReviewConflictAudit;
import ai.cc.chongming.review.domain.model.ReviewConflictAudit.Disposition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewConflictAuditStore;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-024#方案5] Process-local conflict audit store used when persistence is disabled.
 *
 * @author zyj
 */
@Component
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryReviewConflictAuditStore implements ReviewConflictAuditStore {

    private static final Comparator<ReviewConflictAudit> ORDER =
            Comparator.comparing(ReviewConflictAudit::subjectKey);

    private final Map<ReviewId, ConcurrentSkipListMap<Integer, Map<String, ReviewConflictAudit>>> recordsByReview =
            new ConcurrentHashMap<>();

    @Override
    public void replaceBatch(ReviewId reviewId, int attemptNo, Collection<ReviewConflictAudit> records) {
        requireAttempt(reviewId, attemptNo);
        Objects.requireNonNull(records, "records must not be null");
        Map<String, ReviewConflictAudit> replacement = new LinkedHashMap<>();
        for (ReviewConflictAudit record : records) {
            Objects.requireNonNull(record, "record must not be null");
            if (!reviewId.equals(record.reviewId()) || attemptNo != record.attemptNo()) {
                throw new IllegalArgumentException("conflict audit record crosses review attempt boundary");
            }
            if (replacement.putIfAbsent(record.subjectKey(), record) != null) {
                throw new IllegalArgumentException("duplicate conflict audit subject: " + record.subjectKey());
            }
        }
        recordsByReview.computeIfAbsent(reviewId, ignored -> new ConcurrentSkipListMap<>())
                .put(attemptNo, new ConcurrentHashMap<>(replacement));
    }

    @Override
    public void finalizeAttempt(
            ReviewId reviewId,
            int attemptNo,
            Collection<String> registeredSubjectKeys,
            Instant updatedAt) {
        requireAttempt(reviewId, attemptNo);
        Objects.requireNonNull(registeredSubjectKeys, "registeredSubjectKeys must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Map<String, ReviewConflictAudit> records = recordsForAttempt(reviewId, attemptNo);
        if (records == null) {
            return;
        }
        Set<String> registered = registeredSubjectKeys.stream()
                .filter(Objects::nonNull)
                .map(ReviewConflictAudit::normalizeSubjectKey)
                .collect(Collectors.toSet());
        synchronized (records) {
            records.replaceAll((subjectKey, record) -> record.disposition() == Disposition.DETECTED
                    ? record.withDisposition(registered.contains(subjectKey)
                            ? Disposition.REGISTERED : Disposition.SKIPPED, updatedAt)
                    : record);
        }
    }

    @Override
    public List<ReviewConflictAudit> findByReviewAttempt(ReviewId reviewId, int attemptNo) {
        requireAttempt(reviewId, attemptNo);
        Map<String, ReviewConflictAudit> records = recordsForAttempt(reviewId, attemptNo);
        if (records == null) {
            return List.of();
        }
        synchronized (records) {
            return records.values().stream().sorted(ORDER).toList();
        }
    }

    private Map<String, ReviewConflictAudit> recordsForAttempt(ReviewId reviewId, int attemptNo) {
        ConcurrentSkipListMap<Integer, Map<String, ReviewConflictAudit>> attempts = recordsByReview.get(reviewId);
        return attempts == null ? null : attempts.get(attemptNo);
    }

    private void requireAttempt(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }
}
