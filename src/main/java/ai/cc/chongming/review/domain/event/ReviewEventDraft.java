package ai.cc.chongming.review.domain.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-010#1.1] Validated event input before the store allocates a review-global sequence.
 *
 * @author wangli
 */
public record ReviewEventDraft(
        ReviewId reviewId,
        int attemptNo,
        ReviewEventType type,
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

    public ReviewEventDraft {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        if (round != null && (round < 1 || round > 2)) {
            throw new IllegalArgumentException("round must be between 1 and 2");
        }
        if (progress != null && (progress < 0 || progress > 100)) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        if (payloadVersion < 1) {
            throw new IllegalArgumentException("payloadVersion must be positive");
        }
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
        if (payload.keySet().stream().anyMatch(key -> key == null || key.isBlank())
                || payload.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("event payload must contain nonblank keys and nonnull values");
        }
    }
}
