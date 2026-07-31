package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-010#1.2] MySQL append-only event store with a per-review lock for global sequence allocation.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewEventStore implements ReviewEventStore {

    private static final TypeReference<Map<String, String>> STRING_PAYLOAD = new TypeReference<>() {
    };

    private final ReviewPersistenceMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisReviewEventStore(ReviewPersistenceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public ReviewEvent append(ReviewEventDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        String reviewId = draft.reviewId().value().toString();
        if (mapper.lockReviewForEventSequence(reviewId) == null) {
            throw new IllegalStateException("review must exist before appending an event");
        }
        ReviewEvent event = ReviewEvent.committed(mapper.nextReviewEventSequence(reviewId), draft);
        if (mapper.insertReviewEvent(toRow(event)) != 1) {
            throw new IllegalStateException("review event was not appended");
        }
        return event;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewEvent> findAfter(ReviewId reviewId, long afterSequence, int limit) {
        validateReplayRequest(reviewId, afterSequence, limit);
        return mapper.findReviewEventsAfter(reviewId.value().toString(), afterSequence, limit).stream()
                .map(this::toEvent)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewEvent> findLatest(ReviewId reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return Optional.ofNullable(mapper.findLatestReviewEvent(reviewId.value().toString())).map(this::toEvent);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewEvent> findLatestByType(ReviewId reviewId, ReviewEventType eventType) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        return Optional.ofNullable(mapper.findLatestReviewEventByType(
                reviewId.value().toString(), eventType.name())).map(this::toEvent);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewEvent> findLatestByTypeAndAttempt(
            ReviewId reviewId, ReviewEventType eventType, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        return Optional.ofNullable(mapper.findLatestReviewEventByTypeAndAttempt(
                reviewId.value().toString(), eventType.name(), attemptNo)).map(this::toEvent);
    }

    private ReviewPersistenceMapper.ReviewEventRow toRow(ReviewEvent event) {
        return new ReviewPersistenceMapper.ReviewEventRow(
                event.eventId().toString(),
                event.reviewId().value().toString(),
                event.attemptNo(),
                event.sequence(),
                event.type().name(),
                event.category().name(),
                event.stage().name(),
                event.actorRole() == null ? null : event.actorRole().name(),
                event.targetRole() == null ? null : event.targetRole().name(),
                uuid(event.topicId()),
                uuid(event.claimId()),
                uuid(event.turnId()),
                event.round(),
                event.progress(),
                event.payloadVersion(),
                writePayload(event.payload()),
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
    }

    private ReviewEvent toEvent(ReviewPersistenceMapper.ReviewEventRow row) {
        ReviewEventType type = ReviewEventType.valueOf(row.eventType());
        return new ReviewEvent(
                UUID.fromString(row.eventId()),
                row.sequence(),
                new ReviewId(UUID.fromString(row.reviewId())),
                row.attemptNo(),
                type,
                type.category(),
                ReviewStage.valueOf(row.stage()),
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

    private void validateReplayRequest(ReviewId reviewId, long afterSequence, int limit) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (afterSequence < 0 || limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("invalid event replay cursor or limit");
        }
    }

    private String writePayload(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event payload cannot be encoded", exception);
        }
    }

    private Map<String, String> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, STRING_PAYLOAD);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted event payload cannot be decoded", exception);
        }
    }

    private String uuid(TopicId value) {
        return value == null ? null : value.value().toString();
    }

    private String uuid(ClaimId value) {
        return value == null ? null : value.value().toString();
    }

    private String uuid(TurnId value) {
        return value == null ? null : value.value().toString();
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
