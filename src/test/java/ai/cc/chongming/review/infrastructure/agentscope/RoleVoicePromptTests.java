package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePack.Checkpoint;
import ai.cc.chongming.review.domain.role.RolePack.Voice;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * [AIREVIEW-PLAN-032#4.1] Verifies the common expression rules shared by every role and the
 * per-role voice rendering that localizes each role's public language.
 *
 * @author wangli
 */
class RoleVoicePromptTests {

    @Test
    void commonExpressionGuidanceLocalizesProtocolTermsAndBansMachineIdentifiers() {
        String guidance = RoleSubagentFactory.commonExpressionGuidance();

        assertThat(guidance)
                .contains("简体中文")
                .contains("claimId")
                .contains("checkpointKey")
                .contains("OPPOSE→反对")
                .contains("SUPPORT→支持")
                .contains("CHALLENGE→质询")
                .contains("REBUTTAL→答辩")
                .contains("EVIDENCE_REQUEST→证据请求")
                .contains("DEFENSE→需求答辩")
                .contains("Claim→主张")
                .contains("Gate→门禁");
    }

    @Test
    void roleVoiceGuidanceRendersIdentityFocusAvoidAndLens() {
        RolePack pack = pack(new Voice(
                "产品经理身份",
                List.of("目标用户", "成本收益"),
                List.of("实现细节", "机器标识"),
                "用产品语言回答"));

        String guidance = RoleSubagentFactory.roleVoiceGuidance(pack);

        assertThat(guidance)
                .contains("【角色表达规范】")
                .contains("身份：产品经理身份")
                .contains("- 目标用户")
                .contains("- 成本收益")
                .contains("- 实现细节")
                .contains("- 机器标识")
                .contains("检查点表达要求：用产品语言回答");
    }

    @Test
    void emptyVoiceRendersNoRoleGuidance() {
        assertThat(RoleSubagentFactory.roleVoiceGuidance(pack(Voice.EMPTY))).isEmpty();
    }

    @Test
    void everyConfiguredRoleCarriesNonEmptyVoiceGuidance() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());

        assertThat(registry.all())
                .as("every role pack must render role-mother-tongue expression guidance")
                .allSatisfy(rolePack -> assertThat(RoleSubagentFactory.roleVoiceGuidance(rolePack))
                        .contains("【角色表达规范】"));
    }

    @Test
    void productVoiceUsesProductVocabularyInsteadOfImplementationDetails() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());
        Voice productVoice = registry.require(RoleType.PRODUCT).voice();

        assertThat(productVoice.identity()).contains("用户").contains("价值");
        assertThat(productVoice.focus()).contains("目标用户与使用场景");
        assertThat(productVoice.avoid()).anyMatch(item -> item.contains("实现细节"));
    }

    @Test
    void judgeVoiceForbidsProtocolEnglishWordsInVisibleText() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());
        Voice judgeVoice = registry.require(RoleType.JUDGE).voice();

        assertThat(judgeVoice.identity()).contains("裁决");
        assertThat(judgeVoice.avoid()).anyMatch(item -> item.contains("OPPOSE/SUPPORT/CHALLENGE"));
    }

    private RolePack pack(Voice voice) {
        return new RolePack(
                RoleType.BACKEND,
                "Backend reviewer",
                List.of("Always"),
                "backend-v1",
                Set.of("requirement-snapshot"),
                List.of(new Checkpoint("backend.api_contract", "Check API", true)),
                Set.of("searchText"),
                Kind.ROLE_ASSESSMENT,
                "role-reviewer",
                Duration.ofSeconds(30),
                4,
                voice);
    }
}
