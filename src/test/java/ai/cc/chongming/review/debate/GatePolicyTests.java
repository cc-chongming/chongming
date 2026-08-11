package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.domain.gate.GatePolicy;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies safety precedence for AI Gate drafts, including the
 * [AIREVIEW-PLAN-024#方案5] deterministic coverage rules.
 *
 * @author wangli
 */
class GatePolicyTests {

    private static final GatePolicy.RequiredCheckpoint BACKEND_TOKEN =
            new GatePolicy.RequiredCheckpoint(RoleType.BACKEND, "token_expiry_policy");
    private static final GatePolicy.RequiredCheckpoint BACKEND_AUDIT =
            new GatePolicy.RequiredCheckpoint(RoleType.BACKEND, "audit_log_coverage");
    private static final GatePolicy.RequiredCheckpoint PRODUCT_TRACEABILITY =
            new GatePolicy.RequiredCheckpoint(RoleType.PRODUCT, "requirement_traceability");
    private static final Set<GatePolicy.RequiredCheckpoint> ALL_REQUIRED =
            Set.of(BACKEND_TOKEN, BACKEND_AUDIT, PRODUCT_TRACEABILITY);

    @Test
    void requiresHumanWhenHighSeverityClaimHasNoEvidence() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Claim unverified = new Claim(new ClaimId(UUID.randomUUID()), reviewId, RoleType.SECURITY, "authentication",
                ClaimSeverity.P0, ClaimPosition.OPPOSE, "Missing auth policy", "No evidence", List.of()).withStatus(ClaimStatus.UNVERIFIED);

        assertThat(new GatePolicy().draft(reviewId, List.of(unverified), List.of()).result())
                .isEqualTo(GateResult.HUMAN_REQUIRED);
    }

    @Test
    void usesConfiguredNonFinalResultForVerifiedP1Opposition() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Claim opposing = new Claim(new ClaimId(UUID.randomUUID()), reviewId, RoleType.BACKEND, "authentication",
                ClaimSeverity.P1, ClaimPosition.OPPOSE, "Missing expiry policy", "Policy is incomplete", List.of());

        assertThat(new GatePolicy(GateResult.RETURN).draft(reviewId, List.of(opposing), List.of()).result())
                .isEqualTo(GateResult.RETURN);
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] Every non-blocking status may positively cover a required checkpoint.
     */
    @ParameterizedTest
    @EnumSource(value = AssessmentStatus.class,
            names = {"PARTIAL", "GAP", "UNKNOWN"}, mode = EnumSource.Mode.EXCLUDE)
    void aiPassRequiresFullRequiredCoverageWithPositiveStatuses(AssessmentStatus status) {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        List<ReviewAssessment> assessments = List.of(
                assessment(reviewId, BACKEND_TOKEN, status, null),
                assessment(reviewId, BACKEND_AUDIT, status, null),
                assessment(reviewId, PRODUCT_TRACEABILITY, status, null));

        GateDecision draft = new GatePolicy().draft(reviewId, assessments, List.of(), List.of(), ALL_REQUIRED);

        assertThat(draft.result()).isEqualTo(GateResult.AI_PASS);
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] Any missing required checkpoint forces a human, never AI_PASS.
     */
    @ParameterizedTest
    @MethodSource("uncoveredScenarios")
    void uncoveredRequiredCheckpointNeverPassesAutomatically(
            GatePolicy.RequiredCheckpoint missing,
            List<ReviewAssessment> assessments) {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());

        GateDecision draft = new GatePolicy().draft(reviewId, assessments, List.of(), List.of(), ALL_REQUIRED);

        assertThat(draft.result()).isEqualTo(GateResult.HUMAN_REQUIRED);
        assertThat(draft.publicReasonSummary())
                .contains("required checkpoint coverage incomplete")
                .contains(missing.roleType() + ":" + missing.checkpointKey());
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] HIGH-risk UNKNOWN on a required checkpoint never passes automatically,
     * while the same UNKNOWN on a non-required checkpoint is tolerated.
     */
    @ParameterizedTest
    @MethodSource("requiredSlots")
    void highRiskUnknownOnRequiredCheckpointNeverPassesAutomatically(GatePolicy.RequiredCheckpoint slot) {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        List<ReviewAssessment> assessments = new ArrayList<>(fullyCovered(reviewId, AssessmentStatus.CONFIRMED));
        assessments.replaceAll(value -> value.roleType() == slot.roleType()
                && value.checkpointKey().equals(slot.checkpointKey())
                ? assessment(reviewId, slot, AssessmentStatus.UNKNOWN, "缺少授权证据。")
                : value);

        GateDecision draft = new GatePolicy().draft(reviewId, assessments, List.of(), List.of(), ALL_REQUIRED);

        assertThat(draft.result()).isEqualTo(GateResult.HUMAN_REQUIRED);
        assertThat(draft.publicReasonSummary()).contains("high-risk UNKNOWN");
    }

    @Test
    void unknownOnNonRequiredCheckpointDoesNotBlockAiPass() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        List<ReviewAssessment> assessments = new ArrayList<>(fullyCovered(reviewId, AssessmentStatus.CONFIRMED));
        assessments.add(assessment(reviewId,
                new GatePolicy.RequiredCheckpoint(RoleType.FRONTEND, "snapshot_grant_scope"),
                AssessmentStatus.UNKNOWN, "当前评审快照未授予前端文件。"));

        GateDecision draft = new GatePolicy().draft(reviewId, assessments, List.of(), List.of(), ALL_REQUIRED);

        assertThat(draft.result()).isEqualTo(GateResult.AI_PASS);
    }

    @Test
    void requiredGapWithoutTrackedDispositionRequiresHuman() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        List<ReviewAssessment> assessments = new ArrayList<>(fullyCovered(reviewId, AssessmentStatus.CONFIRMED));
        assessments.replaceAll(value -> BACKEND_AUDIT.checkpointKey().equals(value.checkpointKey())
                && value.roleType() == RoleType.BACKEND
                ? assessment(reviewId, BACKEND_AUDIT, AssessmentStatus.GAP, "审计日志缺少敏感操作。")
                : value);

        GateDecision draft = new GatePolicy().draft(reviewId, assessments, List.of(), List.of(), ALL_REQUIRED);

        assertThat(draft.result()).isEqualTo(GateResult.HUMAN_REQUIRED);
        assertThat(draft.publicReasonSummary()).contains("P0/P1 GAP lacks tracked disposition");

        Claim disposition = new Claim(new ClaimId(UUID.randomUUID()), reviewId, RoleType.BACKEND, "authentication",
                ClaimSeverity.P1, ClaimPosition.OPPOSE, "审计缺口处置", "已登记补救计划", List.of());
        GateDecision tracked = new GatePolicy().draft(
                reviewId, assessments, List.of(disposition), List.of(), ALL_REQUIRED);

        // A tracked disposition no longer triggers the coverage-based human escalation; the claim
        // falls back to the configured conservative P1 handling instead of blocking coverage.
        assertThat(tracked.result()).isEqualTo(GateResult.CONDITIONAL);
        assertThat(tracked.publicReasonSummary()).contains("P1 risk requires configured conservative handling");
    }

    @Test
    void judgeConservativeResultsKeepTheirOrderAfterCoverageChecks() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        List<ReviewAssessment> assessments = fullyCovered(reviewId, AssessmentStatus.CONFIRMED);
        TopicId topicId = new TopicId(UUID.randomUUID());

        assertThat(new GatePolicy().draft(reviewId, assessments, List.of(),
                List.of(new JudgeDecision(topicId, GateResult.HUMAN_REQUIRED, "人工复核", List.of(), List.of(), Instant.now())),
                ALL_REQUIRED).result()).isEqualTo(GateResult.HUMAN_REQUIRED);
        assertThat(new GatePolicy().draft(reviewId, assessments, List.of(),
                List.of(new JudgeDecision(topicId, GateResult.BLOCK, "阻断", List.of(), List.of(), Instant.now())),
                ALL_REQUIRED).result()).isEqualTo(GateResult.BLOCK);
        assertThat(new GatePolicy().draft(reviewId, assessments, List.of(),
                List.of(new JudgeDecision(topicId, GateResult.RETURN, "退回", List.of(), List.of(), Instant.now())),
                ALL_REQUIRED).result()).isEqualTo(GateResult.RETURN);
        assertThat(new GatePolicy().draft(reviewId, assessments, List.of(),
                List.of(new JudgeDecision(topicId, GateResult.CONDITIONAL, "条件", List.of(), List.of(), Instant.now())),
                ALL_REQUIRED).result()).isEqualTo(GateResult.CONDITIONAL);
    }

    @Test
    void reasonCarriesServerSideCoverageCounters() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        List<ReviewAssessment> assessments = List.of(
                assessment(reviewId, BACKEND_TOKEN, AssessmentStatus.CONFIRMED, null),
                assessment(reviewId, PRODUCT_TRACEABILITY, AssessmentStatus.CONFIRMED, null),
                assessment(reviewId, BACKEND_AUDIT, AssessmentStatus.PARTIAL, "部分满足。"),
                assessment(reviewId,
                        new GatePolicy.RequiredCheckpoint(RoleType.PROJECT, "milestone_plan"),
                        AssessmentStatus.NOT_APPLICABLE, null),
                assessment(reviewId,
                        new GatePolicy.RequiredCheckpoint(RoleType.FRONTEND, "incremental_render"),
                        AssessmentStatus.UNKNOWN, "缺少授权证据。"));

        GateDecision draft = new GatePolicy().draft(reviewId, assessments, List.of(), List.of(), ALL_REQUIRED);

        assertThat(draft.result()).isEqualTo(GateResult.AI_PASS);
        assertThat(draft.publicReasonSummary()).contains(
                "required=3, confirmed=2, partial=1, gap=0, unknown=1, notApplicable=1");
    }

    static Stream<Arguments> uncoveredScenarios() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        List<ReviewAssessment> full = fullyCovered(reviewId, AssessmentStatus.CONFIRMED);
        return Stream.of(
                Arguments.of(BACKEND_TOKEN, without(full, BACKEND_TOKEN)),
                Arguments.of(BACKEND_AUDIT, without(full, BACKEND_AUDIT)),
                Arguments.of(PRODUCT_TRACEABILITY, without(full, PRODUCT_TRACEABILITY)),
                Arguments.of(BACKEND_TOKEN, List.of()));
    }

    static Stream<GatePolicy.RequiredCheckpoint> requiredSlots() {
        return Stream.of(BACKEND_TOKEN, BACKEND_AUDIT, PRODUCT_TRACEABILITY);
    }

    private static List<ReviewAssessment> without(
            List<ReviewAssessment> assessments, GatePolicy.RequiredCheckpoint slot) {
        List<ReviewAssessment> filtered = new ArrayList<>();
        for (ReviewAssessment assessment : assessments) {
            if (assessment.roleType() == slot.roleType()
                    && assessment.checkpointKey().equals(slot.checkpointKey())) {
                continue;
            }
            filtered.add(assessment);
        }
        return List.copyOf(filtered);
    }

    private static List<ReviewAssessment> fullyCovered(ReviewId reviewId, AssessmentStatus status) {
        Set<GatePolicy.RequiredCheckpoint> slots = new LinkedHashSet<>(ALL_REQUIRED);
        List<ReviewAssessment> assessments = new ArrayList<>();
        for (GatePolicy.RequiredCheckpoint slot : slots) {
            assessments.add(assessment(reviewId, slot, status,
                    status.requiresReasonSummary() ? "已说明原因。" : null));
        }
        return List.copyOf(assessments);
    }

    private static ReviewAssessment assessment(
            ReviewId reviewId,
            GatePolicy.RequiredCheckpoint slot,
            AssessmentStatus status,
            String reasonSummary) {
        return new ReviewAssessment(reviewId, 1, slot.roleType(), slot.checkpointKey(), status,
                "检查点结论摘要。", reasonSummary, List.of(),
                ReviewAssessment.idempotencyKeyFor(reviewId, 1, slot.roleType(), slot.checkpointKey()),
                Instant.parse("2026-08-10T09:00:00Z"));
    }
}
