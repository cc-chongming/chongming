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
 * [AIREVIEW-PLAN-109] Matrix commands additionally carry nullable {@code objectTitle}/
 * {@code objectSubtitle}/{@code objectStatus}/{@code objectHolder} so the mail info card can show
 * the task, requirement, current status and current holder without touching the idempotency key.
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
        String templateKey,
        String objectTitle,
        String objectSubtitle,
        String objectStatus,
        String objectHolder,
        String requirementId) {

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
        objectTitle = objectTitle == null || objectTitle.isBlank() ? null : objectTitle.trim();
        objectSubtitle = objectSubtitle == null || objectSubtitle.isBlank() ? null : objectSubtitle.trim();
        objectStatus = objectStatus == null || objectStatus.isBlank() ? null : objectStatus.trim();
        objectHolder = objectHolder == null || objectHolder.isBlank() ? null : objectHolder.trim();
        requirementId = requirementId == null || requirementId.isBlank() ? null : requirementId.trim();
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
        this(reviewId, gateVersion, channel, destination, result, reason, conditions, reportUrl, null, 0, null, null,
                null, null, null, null, null);
    }

    /**
     * [AIREVIEW-PLAN-030] Matrix command factory for one recipient and channel of one event.
     * [AIREVIEW-PLAN-109] Optional task info ({@code objectTitle}/{@code objectSubtitle}/
     * {@code objectStatus}/{@code objectHolder}) is carried verbatim for the mail info card; all
     * four may be null for events without a task payload.
     */
    public static NotificationCommand forEvent(
            ReviewId reviewId,
            String eventType,
            long eventSequence,
            String channel,
            String destination,
            String recipientUsername,
            String templateKey,
            String title,
            String objectTitle,
            String objectSubtitle,
            String objectStatus,
            String objectHolder,
            String requirementId) {
        return new NotificationCommand(
                reviewId, 0L, channel, destination, null, title, List.of(),
                "/api/reviews/" + reviewId.value() + "/report",
                eventType, eventSequence, recipientUsername, templateKey,
                objectTitle, objectSubtitle, objectStatus, objectHolder, requirementId);
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
