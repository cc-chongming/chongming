package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.util.List;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-011#1.5] Stable domain command sent through a notification adapter.
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
        String reportUrl) {

    public NotificationCommand {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (gateVersion < 1) {
            throw new IllegalArgumentException("gateVersion must be positive");
        }
        channel = requireText(channel, "channel");
        destination = requireText(destination, "destination");
        Objects.requireNonNull(result, "result must not be null");
        reason = requireText(reason, "reason");
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
        reportUrl = requireText(reportUrl, "reportUrl");
    }

    public String idempotencyKey() {
        return reviewId.value() + ":" + gateVersion + ":" + channel;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
