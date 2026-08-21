package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import ai.cc.chongming.review.domain.repository.ReviewPlatformProjectionStore;
import ai.cc.chongming.review.domain.repository.ReviewReportStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPlatformProjectionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H2] Persistent platform projection that pushes filters, total and paging into MySQL.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewPlatformProjectionStore implements ReviewPlatformProjectionStore {

    private static final TypeReference<Map<String, String>> STRING_PAYLOAD = new TypeReference<>() {
    };

    // review_request.updated_at is DB-generated (CURRENT_TIMESTAMP ... ON UPDATE CURRENT_TIMESTAMP)
    // in the MySQL server's China timezone, unlike Java-written columns that follow the UTC
    // wall-clock convention; reading it back as UTC shifted list/dashboard times by +8h.
    private static final ZoneId DB_SERVER_ZONE = ZoneId.of("Asia/Shanghai");

    private final ReviewPlatformProjectionMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisReviewPlatformProjectionStore(ReviewPlatformProjectionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public PlatformReviewPage findReviewPage(ReviewProjectionFilter filter, int page, int size) {
        ReviewProjectionFilter effectiveFilter = filter == null ? new ReviewProjectionFilter(null, null) : filter;
        String stage = effectiveFilter.stage() == null ? null : effectiveFilter.stage().name();
        long offset = ((long) page - 1L) * size;
        List<PlatformReview> items = mapper.findReviewPage(
                        stage, effectiveFilter.hasReport(), effectiveFilter.activeOnly(), offset, size).stream()
                .map(this::toProjection)
                .toList();
        return new PlatformReviewPage(items, mapper.countReviewPage(
                stage, effectiveFilter.hasReport(), effectiveFilter.activeOnly()));
    }

    private PlatformReview toProjection(ReviewPlatformProjectionMapper.PlatformReviewRow row) {
        return new PlatformReview(
                new ReviewId(UUID.fromString(row.reviewId())),
                ReviewStage.valueOf(row.stage()),
                row.attemptNo(),
                row.reviewVersion(),
                row.updatedAt().atZone(DB_SERVER_ZONE).toInstant(),
                row.eventId() == null ? null : toEvent(row),
                row.reportId() == null ? null : toReportMetadata(row));
    }

    private ReviewEvent toEvent(ReviewPlatformProjectionMapper.PlatformReviewRow row) {
        ReviewEventType type = ReviewEventType.valueOf(row.eventType());
        return new ReviewEvent(
                UUID.fromString(row.eventId()),
                row.sequence(),
                new ReviewId(UUID.fromString(row.reviewId())),
                row.eventAttemptNo(),
                type,
                type.category(),
                ReviewStage.valueOf(row.eventStage()),
                role(row.actorRole()),
                role(row.targetRole()),
                topic(row.topicId()),
                claim(row.claimId()),
                turn(row.turnId()),
                row.round(),
                row.progress(),
                row.occurredAt().toInstant(ZoneOffset.UTC),
                row.payloadVersion(),
                readPayload(row.payloadJson()));
    }

    private ReviewReportStore.ReportMetadata toReportMetadata(ReviewPlatformProjectionMapper.PlatformReviewRow row) {
        return new ReviewReportStore.ReportMetadata(
                new ReviewId(UUID.fromString(row.reviewId())),
                row.reportVersion(),
                row.gateVersion() == null ? 1L : row.gateVersion(),
                row.contentHash(),
                row.reportCreatedAt().toInstant(ZoneOffset.UTC));
    }

    private Map<String, String> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, STRING_PAYLOAD);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted event payload cannot be decoded", exception);
        }
    }

    private RoleType role(String value) {
        return value == null ? null : RoleType.valueOf(value);
    }

    private TopicId topic(String value) {
        return value == null ? null : new TopicId(UUID.fromString(value));
    }

    private ClaimId claim(String value) {
        return value == null ? null : new ClaimId(UUID.fromString(value));
    }

    private TurnId turn(String value) {
        return value == null ? null : new TurnId(UUID.fromString(value));
    }
}
