package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-010#1.1][AIREVIEW-PLAN-010#1.5][AIREVIEW-PLAN-024#方案3] Creates formal events
 * only from completed domain commands.
 *
 * @author wangli
 */
public final class ReviewEventDrafts {

    private ReviewEventDrafts() {
    }

    public static ReviewEventDraft completedCommand(
            Review review,
            ReviewEventType type,
            RoleType actorRole,
            RoleType targetRole,
            TopicId topicId,
            ClaimId claimId,
            TurnId turnId,
            Integer round,
            Integer progress,
            Map<String, String> payload) {
        Objects.requireNonNull(review, "review must not be null");
        return new ReviewEventDraft(
                review.id(),
                review.attemptNo(),
                type,
                review.stage(),
                actorRole,
                targetRole,
                topicId,
                claimId,
                turnId,
                round,
                progress,
                null,
                1,
                payload == null ? Map.of() : payload);
    }

    /**
     * [AIREVIEW-PLAN-024#方案3] Drafts one dispatch-command lifecycle fact
     * (ISSUED/CONSUMED/EXPIRED/REJECTED). The recipient role is carried as the event target and
     * the command identity travels in the payload so restart recovery can replay the envelope.
     */
    public static ReviewEventDraft dispatchCommand(
            Review review,
            ReviewEventType type,
            ReviewDispatchCommand command,
            RoleType actorRole,
            String reason,
            Map<String, String> extraPayload) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("commandId", command.commandId().value().toString());
        payload.put("allowedAction", command.allowedAction().name());
        payload.put("dispatchStatus", command.status().name());
        payload.put("recipientRole", command.recipientRole().name());
        if (command.targetClaimId() != null) {
            payload.put("targetClaimId", command.targetClaimId().value().toString());
        }
        if (command.targetTurnId() != null) {
            payload.put("targetTurnId", command.targetTurnId().value().toString());
        }
        payload.put("expiresAt", command.expiresAt().toString());
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }
        if (extraPayload != null) {
            extraPayload.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    payload.put(key, value);
                }
            });
        }
        return new ReviewEventDraft(
                review.id(),
                review.attemptNo(),
                type,
                review.stage(),
                actorRole,
                command.recipientRole(),
                command.topicId(),
                command.targetClaimId(),
                command.targetTurnId(),
                command.round(),
                null,
                null,
                1,
                payload);
    }
}
