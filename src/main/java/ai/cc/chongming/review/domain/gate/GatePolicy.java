package ai.cc.chongming.review.domain.gate;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * Produces a conservative AI Gate draft from immutable Claims, Judge conclusions and, since
 * [AIREVIEW-PLAN-024#方案5], five-status checkpoint assessments. It never creates a final decision.
 *
 * <p>Deterministic safety precedence (方案5): required checkpoint coverage gaps always require a
 * human before any other rule; P0/P1 gaps without verified evidence or tracked disposition block;
 * high-risk UNKNOWN conclusions on required checkpoints require a human; Judge RETURN/BLOCK/
 * CONDITIONAL keep the existing conservative order; {@code AI_PASS} is only possible when every
 * required checkpoint is covered and no blocking item remains.
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
     * Legacy draft entry without assessment coverage inputs; retained so callers without the
     * assessment store keep the pre-方案5 conservative behaviour.
     */
    public GateDecision draft(ReviewId reviewId, List<Claim> claims, List<JudgeDecision> judgeDecisions) {
        return draft(reviewId, List.of(), claims, judgeDecisions, Set.of());
    }

    /**
     * Applies the deterministic 方案5 precedence over assessments, claims and Judge conclusions.
     *
     * @param assessments all persisted assessments of the current attempt (one batch query)
     * @param requiredCheckpoints required (role, checkpointKey) slots derived from the core RolePacks
     */
    public GateDecision draft(
            ReviewId reviewId,
            List<ReviewAssessment> assessments,
            List<Claim> claims,
            List<JudgeDecision> judgeDecisions,
            Set<RequiredCheckpoint> requiredCheckpoints) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        List<ReviewAssessment> safeAssessments = assessments == null ? List.of() : List.copyOf(assessments);
        List<Claim> safeClaims = claims == null ? List.of() : List.copyOf(claims);
        List<JudgeDecision> safeJudgements = judgeDecisions == null ? List.of() : List.copyOf(judgeDecisions);
        Set<RequiredCheckpoint> safeRequired =
                requiredCheckpoints == null ? Set.of() : Set.copyOf(requiredCheckpoints);
        boolean coverageMode = !safeAssessments.isEmpty() || !safeRequired.isEmpty();

        Map<AssessmentStatus, Long> counts = countByStatus(safeAssessments);
        String coverageLine = coverageLine(safeRequired.size(), counts);

        GateResult result;
        String detail;
        Set<RequiredCheckpoint> uncovered = uncoveredRequired(safeRequired, safeAssessments);
        Set<RoleType> opposeClaimRoles = opposeClaimRoles(safeClaims);
        if (!uncovered.isEmpty()) {
            result = GateResult.HUMAN_REQUIRED;
            detail = "required checkpoint coverage incomplete: uncovered=" + describe(uncovered);
        } else if (safeClaims.stream().anyMatch(claim -> claim.severity().requiresEvidenceForAutoBlock()
                && claim.status() == ClaimStatus.UNVERIFIED)) {
            result = GateResult.HUMAN_REQUIRED;
            detail = "P0/P1 claim lacks verified evidence";
        } else if (hasUntrackedRequiredGap(safeRequired, safeAssessments, opposeClaimRoles)) {
            result = GateResult.HUMAN_REQUIRED;
            detail = "P0/P1 GAP lacks tracked disposition or evidence";
        } else if (hasHighRiskUnknown(safeRequired, safeAssessments)) {
            result = GateResult.HUMAN_REQUIRED;
            detail = "high-risk UNKNOWN on required checkpoint";
        } else if (safeJudgements.stream().anyMatch(decision -> decision.result() == GateResult.HUMAN_REQUIRED)) {
            result = GateResult.HUMAN_REQUIRED;
            detail = "Judge requires human review";
        } else if (safeJudgements.stream().anyMatch(decision -> decision.result() == GateResult.BLOCK)
                || safeClaims.stream().anyMatch(claim -> claim.severity() == ClaimSeverity.P0
                && claim.position() == ClaimPosition.OPPOSE)) {
            result = GateResult.BLOCK;
            detail = "P0 blocking risk remains";
        } else if (safeJudgements.stream().anyMatch(decision -> decision.result() == GateResult.RETURN)) {
            result = GateResult.RETURN;
            detail = "Judge requested requirement revision";
        } else if (safeJudgements.stream().anyMatch(decision -> decision.result() == GateResult.CONDITIONAL)) {
            result = GateResult.CONDITIONAL;
            detail = "Judge requires a tracked condition";
        } else if (safeClaims.stream().anyMatch(claim -> claim.severity() == ClaimSeverity.P1
                && claim.position() == ClaimPosition.OPPOSE)) {
            result = p1OpposeResult;
            detail = "P1 risk requires configured conservative handling";
        } else {
            result = GateResult.AI_PASS;
            detail = coverageMode
                    ? "required checkpoints fully covered with no blocking item"
                    : "No unresolved blocking Claim or Judge condition";
        }
        String reason = coverageMode ? coverageLine + "; " + detail : detail;
        return new GateDecision(reviewId, result, DecisionStatus.DRAFT, DecisionActor.AI, reason, Instant.now());
    }

    private static Map<AssessmentStatus, Long> countByStatus(List<ReviewAssessment> assessments) {
        Map<AssessmentStatus, Long> counts = new EnumMap<>(AssessmentStatus.class);
        for (AssessmentStatus status : AssessmentStatus.values()) {
            counts.put(status, 0L);
        }
        for (ReviewAssessment assessment : assessments) {
            counts.merge(assessment.status(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * Server-side coverage counters, e.g.
     * {@code required=24, confirmed=15, partial=4, gap=3, unknown=2, notApplicable=0}.
     */
    private static String coverageLine(int required, Map<AssessmentStatus, Long> counts) {
        return "required=" + required
                + ", confirmed=" + counts.get(AssessmentStatus.CONFIRMED)
                + ", partial=" + counts.get(AssessmentStatus.PARTIAL)
                + ", gap=" + counts.get(AssessmentStatus.GAP)
                + ", unknown=" + counts.get(AssessmentStatus.UNKNOWN)
                + ", notApplicable=" + counts.get(AssessmentStatus.NOT_APPLICABLE);
    }

    private static Set<RequiredCheckpoint> uncoveredRequired(
            Set<RequiredCheckpoint> required, List<ReviewAssessment> assessments) {
        Set<RequiredCheckpoint> covered = new java.util.HashSet<>();
        for (ReviewAssessment assessment : assessments) {
            covered.add(new RequiredCheckpoint(assessment.roleType(), assessment.checkpointKey()));
        }
        Set<RequiredCheckpoint> uncovered = new TreeSet<>(
                java.util.Comparator.comparing((RequiredCheckpoint checkpoint) -> checkpoint.roleType().name())
                        .thenComparing(RequiredCheckpoint::checkpointKey));
        for (RequiredCheckpoint checkpoint : required) {
            if (!covered.contains(checkpoint)) {
                uncovered.add(checkpoint);
            }
        }
        return uncovered;
    }

    /**
     * A GAP on a required checkpoint must be tracked by an OPPOSE claim of the same role (its
     * disposition/evidence carrier); otherwise the gate cannot verify its handling.
     */
    private static boolean hasUntrackedRequiredGap(
            Set<RequiredCheckpoint> required,
            List<ReviewAssessment> assessments,
            Set<RoleType> opposeClaimRoles) {
        for (ReviewAssessment assessment : assessments) {
            if (assessment.status() != AssessmentStatus.GAP) {
                continue;
            }
            RequiredCheckpoint slot = new RequiredCheckpoint(assessment.roleType(), assessment.checkpointKey());
            if (required.contains(slot) && !opposeClaimRoles.contains(assessment.roleType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * UNKNOWN conclusions on required checkpoints are high-risk by definition: the authorized
     * evidence was insufficient, so a human must verify before any pass.
     */
    private static boolean hasHighRiskUnknown(Set<RequiredCheckpoint> required, List<ReviewAssessment> assessments) {
        for (ReviewAssessment assessment : assessments) {
            if (assessment.status() == AssessmentStatus.UNKNOWN
                    && required.contains(new RequiredCheckpoint(assessment.roleType(), assessment.checkpointKey()))) {
                return true;
            }
        }
        return false;
    }

    private static Set<RoleType> opposeClaimRoles(List<Claim> claims) {
        Set<RoleType> roles = new java.util.HashSet<>();
        for (Claim claim : claims) {
            if (claim.position() == ClaimPosition.OPPOSE && claim.status() != ClaimStatus.WITHDRAWN) {
                roles.add(claim.roleType());
            }
        }
        return roles;
    }

    private static String describe(Set<RequiredCheckpoint> checkpoints) {
        StringBuilder builder = new StringBuilder();
        for (RequiredCheckpoint checkpoint : checkpoints) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(checkpoint.roleType().name()).append(':').append(checkpoint.checkpointKey());
        }
        return builder.toString();
    }

    /**
     * One required checkpoint slot owned by one core role; the required set is derived by callers
     * from the RolePackRegistry checkpoint contract.
     *
     * @author wangli
     */
    public record RequiredCheckpoint(RoleType roleType, String checkpointKey) {
        public RequiredCheckpoint {
            Objects.requireNonNull(roleType, "roleType must not be null");
            if (checkpointKey == null || checkpointKey.isBlank()) {
                throw new IllegalArgumentException("checkpointKey must not be blank");
            }
        }
    }
}
