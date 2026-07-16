package ai.cc.chongming.review.domain.gateway;

import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public JSON contracts accepted from models before the domain protocol applies its final validation.
 *
 * @author wangli
 */
public final class StructuredOutputs {

    private StructuredOutputs() {
    }

    /**
     * Supported model output contract kinds.
     *
     * @author wangli
     */
    public enum Kind {
        PLAN,
        ROLE_ASSESSMENT,
        JUDGE_DECISION
    }

    /**
     * Plan-mode public output. Workflow code still assigns versions and checks allowed transitions.
     *
     * @author wangli
     */
    public record PlanOutput(List<PlanTask> tasks, String changeReason) {

        public PlanOutput {
            tasks = List.copyOf(tasks);
            if (tasks.isEmpty()) {
                throw new IllegalArgumentException("Plan output must contain at least one task");
            }
            requireText(changeReason, "changeReason");
        }
    }

    /**
     * One model-proposed public plan task.
     *
     * @author wangli
     */
    public record PlanTask(String title, String reason) {

        public PlanTask {
            requireText(title, "title");
            requireText(reason, "reason");
        }
    }

    /**
     * Role-review output containing only candidate claims; the protocol guard creates real claims later.
     *
     * @author wangli
     */
    public record RoleAssessmentOutput(List<ClaimCandidate> claims, String publicSummary) {

        public RoleAssessmentOutput {
            claims = List.copyOf(claims);
            requireText(publicSummary, "publicSummary");
        }
    }

    /**
     * Candidate claim whose evidence IDs and enums are syntax-checked before domain validation.
     *
     * @author wangli
     */
    public record ClaimCandidate(
            String subjectKey,
            ClaimSeverity severity,
            ClaimPosition position,
            String statement,
            String reasonSummary,
            List<String> evidenceIds) {

        public ClaimCandidate {
            requireText(subjectKey, "subjectKey");
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(position, "position must not be null");
            requireText(statement, "statement");
            requireText(reasonSummary, "reasonSummary");
            evidenceIds = List.copyOf(evidenceIds);
            evidenceIds.forEach(StructuredOutputs::requireUuid);
        }
    }

    /**
     * Judge output that may be converted to a domain decision only after topic and evidence checks.
     *
     * @author wangli
     */
    public record JudgeDecisionOutput(
            String topicId,
            GateResult result,
            String publicReasonSummary,
            List<String> evidenceIds,
            List<String> targetClaimIds) {

        public JudgeDecisionOutput {
            requireUuid(topicId);
            Objects.requireNonNull(result, "result must not be null");
            requireText(publicReasonSummary, "publicReasonSummary");
            evidenceIds = List.copyOf(evidenceIds);
            targetClaimIds = List.copyOf(targetClaimIds);
            evidenceIds.forEach(StructuredOutputs::requireUuid);
            targetClaimIds.forEach(StructuredOutputs::requireUuid);
        }
    }

    private static void requireUuid(String value) {
        requireText(value, "id");
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("id must be a UUID", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
