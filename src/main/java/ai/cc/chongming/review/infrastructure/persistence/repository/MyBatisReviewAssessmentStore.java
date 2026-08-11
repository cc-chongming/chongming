package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.ReviewAssessmentStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewAssessmentPersistenceMapper;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewAssessmentPersistenceMapper.AssessmentRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * [AIREVIEW-PLAN-024#方案5] Durable assessment store used whenever review persistence is enabled.
 * Mirrors {@code InMemoryReviewAssessmentStore} semantics: a batch must belong to one review
 * attempt, must not contain duplicate (role, checkpointKey) pairs, and repeated submissions for the
 * same identity are idempotent with the latest submission winning. All reads are single batch
 * queries; per-checkpoint database round-trips are intentionally impossible here.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewAssessmentStore implements ReviewAssessmentStore {

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final ReviewAssessmentPersistenceMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisReviewAssessmentStore(ReviewAssessmentPersistenceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public void saveBatch(ReviewId reviewId, int attemptNo, Collection<ReviewAssessment> assessments) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(assessments, "assessments must not be null");
        if (assessments.isEmpty()) {
            throw new IllegalArgumentException("assessments must not be empty");
        }
        Set<String> batchKeys = new HashSet<>();
        List<AssessmentRow> rows = new java.util.ArrayList<>(assessments.size());
        for (ReviewAssessment assessment : assessments) {
            Objects.requireNonNull(assessment, "assessment must not be null");
            if (!reviewId.equals(assessment.reviewId()) || attemptNo != assessment.attemptNo()) {
                throw new IllegalArgumentException(
                        "assessment does not belong to review " + reviewId.value() + " attempt " + attemptNo);
            }
            if (!batchKeys.add(assessment.storageKey())) {
                throw new IllegalArgumentException("duplicate assessment in batch: " + assessment.storageKey());
            }
            rows.add(toRow(assessment));
        }
        mapper.upsertBatch(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewAssessment> findByReview(ReviewId reviewId, int attemptNo) {
        requireAttempt(reviewId, attemptNo);
        return mapper.findByAttempt(reviewId.value().toString(), attemptNo).stream()
                .map(this::toAssessment)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewAssessment> findByReview(ReviewId reviewId, int attemptNo, RoleType roleType) {
        requireAttempt(reviewId, attemptNo);
        Objects.requireNonNull(roleType, "roleType must not be null");
        return mapper.findByAttemptAndRole(reviewId.value().toString(), attemptNo, roleType.name()).stream()
                .map(this::toAssessment)
                .toList();
    }

    private void requireAttempt(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }

    private AssessmentRow toRow(ReviewAssessment assessment) {
        return new AssessmentRow(
                assessment.reviewId().value().toString(),
                assessment.attemptNo(),
                assessment.roleType().name(),
                assessment.checkpointKey(),
                assessment.status().name(),
                assessment.summary(),
                assessment.reasonSummary(),
                assessment.evidenceIds().isEmpty()
                        ? null
                        : write(assessment.evidenceIds().stream().map(id -> id.value().toString()).toList()),
                assessment.idempotencyKey().value(),
                assessment.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    private ReviewAssessment toAssessment(AssessmentRow row) {
        return new ReviewAssessment(
                new ReviewId(UUID.fromString(row.reviewId())),
                row.attemptNo(),
                RoleType.valueOf(row.roleType()),
                row.checkpointKey(),
                AssessmentStatus.valueOf(row.status()),
                row.summary(),
                row.reasonSummary(),
                readEvidenceIds(row.evidenceIdsJson()),
                new IdempotencyKey(row.idempotencyKey()),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("assessment data could not be serialized", exception);
        }
    }

    private List<EvidenceId> readEvidenceIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST).stream()
                    .map(value -> new EvidenceId(UUID.fromString(value)))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("assessment evidence id list could not be parsed", exception);
        }
    }
}
