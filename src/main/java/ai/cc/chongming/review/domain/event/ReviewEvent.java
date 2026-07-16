package ai.cc.chongming.review.domain.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-010#1.1] Immutable, append-only review fact with a review-global replay sequence.
 *
 * @author wangli
 */
public record ReviewEvent(
        UUID eventId,
        long sequence,
        ReviewId reviewId,
        int attemptNo,
        ReviewEventType type,
        ReviewEventCategory category,
        ReviewStage stage,
        RoleType actorRole,
        RoleType targetRole,
        TopicId topicId,
        ClaimId claimId,
        TurnId turnId,
        Integer round,
        Integer progress,
        Instant occurredAt,
        int payloadVersion,
        Map<String, String> payload) {

    public ReviewEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(category, "category must not be null");
        if (type.category() != category) {
            throw new IllegalArgumentException("event category must match event type");
        }
        Objects.requireNonNull(stage, "stage must not be null");
        if (round != null && (round < 1 || round > 2)) {
            throw new IllegalArgumentException("round must be between 1 and 2");
        }
        if (progress != null && (progress < 0 || progress > 100)) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (payloadVersion < 1) {
            throw new IllegalArgumentException("payloadVersion must be positive");
        }
        payload = Map.copyOf(payload);
    }

    public static ReviewEvent committed(long sequence, ReviewEventDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        return new ReviewEvent(
                UUID.randomUUID(),
                sequence,
                draft.reviewId(),
                draft.attemptNo(),
                draft.type(),
                draft.type().category(),
                draft.stage(),
                draft.actorRole(),
                draft.targetRole(),
                draft.topicId(),
                draft.claimId(),
                draft.turnId(),
                draft.round(),
                draft.progress(),
                draft.occurredAt(),
                draft.payloadVersion(),
                draft.payload());
    }
}
