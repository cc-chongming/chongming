package ai.cc.chongming.review.model;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.domain.gateway.ModelFailurePolicy;
import ai.cc.chongming.review.domain.gateway.ModelFailurePolicy.Disposition;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException.Code;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import org.junit.jupiter.api.Test;

/**
 * Tests safe deterministic model-failure dispositions before workflow orchestration is added.
 *
 * @author wangli
 */
class ModelFailurePolicyTests {

    private final ModelFailurePolicy policy = new ModelFailurePolicy();

    @Test
    void neverAllowsCoreRoleOrJudgeFailureToProduceAnAiPass() {
        assertThat(policy.decide(RoleType.BACKEND, Code.MODEL_NETWORK_ERROR)).isEqualTo(Disposition.HUMAN_REQUIRED);
        assertThat(policy.decide(RoleType.SECURITY, Code.MODEL_RATE_LIMITED))
                .isEqualTo(Disposition.PARTIAL_COMPLETION);
        assertThat(policy.decide(RoleType.JUDGE, Code.MODEL_RESPONSE_INVALID))
                .isEqualTo(Disposition.DETERMINISTIC_GATE_DRAFT);
        assertThat(policy.allModelsUnavailable()).isEqualTo(Disposition.HUMAN_REQUIRED_EVIDENCE_ONLY);
    }
}
