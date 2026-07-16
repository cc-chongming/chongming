package ai.cc.chongming.review.domain.gateway;

import ai.cc.chongming.review.domain.gateway.ModelGatewayException.Code;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.util.Objects;

/**
 * Maps model failures to deterministic workflow dispositions without fabricating an AI conclusion.
 *
 * @author wangli
 */
public class ModelFailurePolicy {

    /**
     * Decides the minimum safe workflow response for one role's failed model call.
     *
     * @param roleType failed role
     * @param failureCode stable model failure category
     * @return safe next action
     */
    public Disposition decide(RoleType roleType, Code failureCode) {
        Objects.requireNonNull(roleType, "roleType must not be null");
        Objects.requireNonNull(failureCode, "failureCode must not be null");
        if (roleType == RoleType.JUDGE) {
            return Disposition.DETERMINISTIC_GATE_DRAFT;
        }
        if (roleType.isCore()) {
            return Disposition.HUMAN_REQUIRED;
        }
        return Disposition.PARTIAL_COMPLETION;
    }

    /**
     * Determines the only safe outcome when no configured model profile remains available.
     *
     * @return evidence-and-rule-only human escalation
     */
    public Disposition allModelsUnavailable() {
        return Disposition.HUMAN_REQUIRED_EVIDENCE_ONLY;
    }

    /**
     * Workflow dispositions consumed by later harness and Gate orchestration plans.
     *
     * @author wangli
     */
    public enum Disposition {
        PARTIAL_COMPLETION,
        DETERMINISTIC_GATE_DRAFT,
        HUMAN_REQUIRED,
        HUMAN_REQUIRED_EVIDENCE_ONLY
    }
}
