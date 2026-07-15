package ai.cc.chongming.review.domain.model;

import java.time.Instant;
import java.util.Objects;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-003#1.2] Represents an auditable AI draft or human final Gate decision.
 *
 * @author wangli
 */
public record GateDecision(
        ReviewId reviewId,
        GateResult result,
        DecisionStatus status,
        DecisionActor actor,
        String publicReasonSummary,
        Instant decidedAt) {

    public GateDecision {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (publicReasonSummary == null || publicReasonSummary.isBlank()) {
            throw new IllegalArgumentException("publicReasonSummary must not be blank");
        }
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    }
}

