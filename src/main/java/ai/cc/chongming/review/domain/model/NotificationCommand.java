package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.util.List;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-011#1.5] Stable domain command sent through a notification adapter.
 * [AIREVIEW-PLAN-030] Generalized from "final Gate result notification" to "transition event
 * notification": matrix rows carry {@code eventType}/{@code eventSequence}/{@code recipientUsername}/
 * {@code templateKey} and leave the Gate-specific {@code result} null; legacy Gate commands keep
 * the historical shape and idempotency key.
 *
 * @author wangli
 */
public record NotificationCommand(
        ReviewId reviewId,
        long gateVersion,
        String channel,
        String destination,
        GateResult result,
        String reason,
        List<String> conditions,
        String reportUrl,
        String eventType,
        long eventSequence,
        String recipientUsername,
        String templateKey) {

    public NotificationCommand {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (gateVersion < 0) {
            throw new IllegalArgumentException("gateVersion must not be negative");
        }
        boolean matrixCommand = eventType != null && !eventType.isBlank();
        if (!matrixCommand && gateVersion < 1) {
            throw new IllegalArgumentException("gateVersion must be positive for Gate notifications");
        }
        channel = requireText(channel, "channel");
        destination = requireText(destination, "destination");
        if (!matrixCommand) {
            Objects.requireNonNull(result, "result must not be null for Gate notifications");
        }
        reason = requireText(reason, "reason");
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
        reportUrl = requireText(reportUrl, "reportUrl");
        eventType = matrixCommand ? eventType.trim() : null;
        if (eventSequence < 0) {
            throw new IllegalArgumentException("eventSequence must not be negative");
        }
        recipientUsername = recipientUsername == null || recipientUsername.isBlank() ? null : recipientUsername.trim();
        templateKey = templateKey == null || templateKey.isBlank() ? null : templateKey.trim();
    }

    /** Legacy Gate-result command shape. */
    public NotificationCommand(
            ReviewId reviewId,
            long gateVersion,
            String channel,
            String destination,
            GateResult result,
            String reason,
            List<String> conditions,
            String reportUrl) {
        this(reviewId, gateVersion, channel, destination, result, reason, conditions, reportUrl, null, 0, null, null);
    }

    /**
     * [AIREVIEW-PLAN-030] Matrix command factory for one recipient and channel of one event.
     */
    public static NotificationCommand forEvent(
            ReviewId reviewId,
            String eventType,
            long eventSequence,
            String channel,
            String destination,
            String recipientUsername,
            String templateKey,
            String title) {
        return new NotificationCommand(
                reviewId, 0L, channel, destination, null, title, List.of(),
                "/api/reviews/" + reviewId.value() + "/report",
                eventType, eventSequence, recipientUsername, templateKey);
    }

    public String idempotencyKey() {
        if (eventType != null) {
            return reviewId.value() + ":" + eventType + ":" + eventSequence + ":"
                    + (recipientUsername == null ? "-" : recipientUsername) + ":" + channel;
        }
        return reviewId.value() + ":" + gateVersion + ":" + channel;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
