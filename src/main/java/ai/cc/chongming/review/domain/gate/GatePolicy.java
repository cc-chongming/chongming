package ai.cc.chongming.review.domain.gate;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.GateDecision;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * Produces a conservative AI Gate draft from immutable Claims and Judge conclusions; it never creates a final decision.
 *
 * @author wangli
 */
public final class GatePolicy {

    private final GateResult p1OpposeResult;

    public GatePolicy() {
        this(GateResult.CONDITIONAL);
    }

    public GatePolicy(GateResult p1OpposeResult) {
        if (p1OpposeResult == null || p1OpposeResult == GateResult.AI_PASS
                || p1OpposeResult == GateResult.PASS || p1OpposeResult == GateResult.OVERRIDE) {
            throw new IllegalArgumentException("p1OpposeResult must be a conservative non-final AI Gate result");
        }
        this.p1OpposeResult = p1OpposeResult;
    }

    /**
     * Applies deterministic safety precedence: unverified high severity evidence requires a human before any pass.
     */
    public GateDecision draft(ReviewId reviewId, List<Claim> claims, List<JudgeDecision> judgeDecisions) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        List<Claim> safeClaims = claims == null ? List.of() : List.copyOf(claims);
        List<JudgeDecision> safeJudgements = judgeDecisions == null ? List.of() : List.copyOf(judgeDecisions);
        GateResult result;
        String reason;
        if (safeClaims.stream().anyMatch(claim -> claim.severity().requiresEvidenceForAutoBlock()
                && claim.status() == ClaimStatus.UNVERIFIED)) {
            result = GateResult.HUMAN_REQUIRED;
            reason = "P0/P1 claim lacks verified evidence";
        } else if (safeJudgements.stream().anyMatch(decision -> decision.result() == GateResult.HUMAN_REQUIRED)) {
            result = GateResult.HUMAN_REQUIRED;
            reason = "Judge requires human review";
        } else if (safeJudgements.stream().anyMatch(decision -> decision.result() == GateResult.BLOCK)
                || safeClaims.stream().anyMatch(claim -> claim.severity() == ClaimSeverity.P0
                && claim.position() == ClaimPosition.OPPOSE)) {
            result = GateResult.BLOCK;
            reason = "P0 blocking risk remains";
        } else if (safeJudgements.stream().anyMatch(decision -> decision.result() == GateResult.RETURN)) {
            result = GateResult.RETURN;
            reason = "Judge requested requirement revision";
        } else if (safeJudgements.stream().anyMatch(decision -> decision.result() == GateResult.CONDITIONAL)) {
            result = GateResult.CONDITIONAL;
            reason = "Judge requires a tracked condition";
        } else if (safeClaims.stream().anyMatch(claim -> claim.severity() == ClaimSeverity.P1
                && claim.position() == ClaimPosition.OPPOSE)) {
            result = p1OpposeResult;
            reason = "P1 risk requires configured conservative handling";
        } else {
            result = GateResult.AI_PASS;
            reason = "No unresolved blocking Claim or Judge condition";
        }
        return new GateDecision(reviewId, result, DecisionStatus.DRAFT, DecisionActor.AI, reason, Instant.now());
    }
}
