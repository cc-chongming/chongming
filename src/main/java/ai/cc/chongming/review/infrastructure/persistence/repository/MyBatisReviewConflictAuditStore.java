package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewConflictAudit;
import ai.cc.chongming.review.domain.model.ReviewConflictAudit.Disposition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewConflictAuditStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewConflictAuditPersistenceMapper;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewConflictAuditPersistenceMapper.ConflictAuditRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-024#方案5] Durable conflict audit store for restart-safe detection disposition.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewConflictAuditStore implements ReviewConflictAuditStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ReviewConflictAuditPersistenceMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisReviewConflictAuditStore(
            ReviewConflictAuditPersistenceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public void replaceBatch(
            ReviewId reviewId, int attemptNo, Collection<ReviewConflictAudit> records) {
        requireAttempt(reviewId, attemptNo);
        Objects.requireNonNull(records, "records must not be null");
        Set<String> subjects = new HashSet<>();
        List<ConflictAuditRow> rows = records.stream().map(record -> {
            Objects.requireNonNull(record, "record must not be null");
            if (!reviewId.equals(record.reviewId()) || attemptNo != record.attemptNo()) {
                throw new IllegalArgumentException("conflict audit record crosses review attempt boundary");
            }
            if (!subjects.add(record.subjectKey())) {
                throw new IllegalArgumentException("duplicate conflict audit subject: " + record.subjectKey());
            }
            return toRow(record);
        }).toList();
        mapper.deleteByAttempt(reviewId.value().toString(), attemptNo);
        if (!rows.isEmpty()) {
            mapper.insertBatch(rows);
        }
    }

    @Override
    @Transactional
    public void finalizeAttempt(
            ReviewId reviewId,
            int attemptNo,
            Collection<String> registeredSubjectKeys,
            Instant updatedAt) {
        requireAttempt(reviewId, attemptNo);
        Objects.requireNonNull(registeredSubjectKeys, "registeredSubjectKeys must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        String reviewValue = reviewId.value().toString();
        List<String> hashes = registeredSubjectKeys.stream()
                .filter(Objects::nonNull)
                .map(subjectKey -> subjectHash(reviewId, attemptNo, subjectKey))
                .distinct()
                .toList();
        mapper.finalizeDetected(
                reviewValue, attemptNo, hashes, updatedAt.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewConflictAudit> findByReviewAttempt(ReviewId reviewId, int attemptNo) {
        requireAttempt(reviewId, attemptNo);
        String reviewValue = reviewId.value().toString();
        return mapper.findByAttempt(reviewValue, attemptNo).stream().map(this::toAudit).toList();
    }

    private ConflictAuditRow toRow(ReviewConflictAudit record) {
        return new ConflictAuditRow(
                record.reviewId().value().toString(),
                record.attemptNo(),
                record.subjectHash(),
                record.subjectKey(),
                record.claimIds().isEmpty()
                        ? null
                        : write(record.claimIds().stream().map(id -> id.value().toString()).toList()),
                record.rules(),
                record.disposition().name(),
                record.updatedAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    private ReviewConflictAudit toAudit(ConflictAuditRow row) {
        return new ReviewConflictAudit(
                new ReviewId(UUID.fromString(row.reviewId())),
                row.attemptNo(),
                row.subjectKey(),
                readClaimIds(row.claimIdsJson()),
                row.rules(),
                Disposition.valueOf(row.disposition()),
                row.updatedAt().toInstant(ZoneOffset.UTC));
    }

    private String subjectHash(ReviewId reviewId, int attemptNo, String subjectKey) {
        requireAttempt(reviewId, attemptNo);
        return ReviewConflictAudit.subjectHashFor(subjectKey);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("conflict audit data could not be serialized", exception);
        }
    }

    private List<ClaimId> readClaimIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST).stream()
                    .map(value -> new ClaimId(UUID.fromString(value)))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("conflict audit claim id list could not be parsed", exception);
        }
    }

    private void requireAttempt(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }
}
