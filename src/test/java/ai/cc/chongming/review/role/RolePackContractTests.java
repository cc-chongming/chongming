package ai.cc.chongming.review.role;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Tests static RolePack resources, role coverage and server-controlled tool whitelists.
 *
 * @author wangli
 */
class RolePackContractTests {

    @Test
    void loadsCoreOptionalAndJudgePacksWithOnlyWhitelistedTools() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());

        assertThat(registry.all()).hasSize(8);
        assertThat(registry.require(RoleType.PRODUCT).modelProfile()).isEqualTo("role-reviewer");
        assertThat(registry.require(RoleType.JUDGE).allowedTools())
                .containsExactlyInAnyOrder("list_persisted_debate_topics", "submit_judgement", "draft_gate");
        assertThat(registry.require(RoleType.BACKEND).allowedTools())
                .contains("submit_claim", "complete_initial_review", "list_persisted_debate_topics",
                        "submit_challenge", "submit_rebuttal");
        assertThat(registry.require(RoleType.SECURITY).activationRules()).isNotEmpty();
        assertThat(registry.all()).allSatisfy(rolePack -> {
            assertThat(rolePack.promptVersion()).matches("[a-z]+-v\\d+");
            assertThat(rolePack.maxIterations()).isBetween(1, 20);
        });
    }

    @Test
    void givesEveryInitialReviewRoleEnoughTurnsToResearchAndCompleteItsDomainProtocol() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());

        assertThat(registry.all())
                .filteredOn(rolePack -> rolePack.roleType() != RoleType.JUDGE)
                .allSatisfy(rolePack -> assertThat(rolePack.maxIterations()).isEqualTo(20));
    }
}
