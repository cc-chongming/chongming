package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-011#1.3] Immutable, versioned final Gate decision made by a human reviewer.
 *
 * @author wangli
 */
public record HumanGateDecision(
        ReviewId reviewId,
        long gateVersion,
        GateResult result,
        String reason,
        List<String> conditions,
        String overrideReason,
        String reviewerId,
        Long supersedesVersion,
        Instant decidedAt) {

    public HumanGateDecision {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (gateVersion < 1) {
            throw new IllegalArgumentException("gateVersion must be positive");
        }
        if (result != GateResult.PASS && result != GateResult.CONDITIONAL && result != GateResult.BLOCK
                && result != GateResult.RETURN && result != GateResult.OVERRIDE) {
            throw new IllegalArgumentException("human final Gate result is not supported");
        }
        reason = requireText(reason, "reason");
        conditions = List.copyOf(conditions == null ? List.of() : conditions.stream()
                .map(condition -> requireText(condition, "condition"))
                .toList());
        if (result == GateResult.CONDITIONAL && conditions.isEmpty()) {
            throw new IllegalArgumentException("CONDITIONAL requires at least one condition");
        }
        if (result != GateResult.CONDITIONAL && !conditions.isEmpty()) {
            throw new IllegalArgumentException("only CONDITIONAL may contain conditions");
        }
        if (result == GateResult.OVERRIDE) {
            overrideReason = requireText(overrideReason, "overrideReason");
        } else if (overrideReason != null && !overrideReason.isBlank()) {
            throw new IllegalArgumentException("overrideReason is only allowed for OVERRIDE");
        } else {
            overrideReason = null;
        }
        reviewerId = requireText(reviewerId, "reviewerId");
        if (supersedesVersion != null && (supersedesVersion < 1 || supersedesVersion >= gateVersion)) {
            throw new IllegalArgumentException("supersedesVersion must identify an earlier Gate version");
        }
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
