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
                .containsExactlyInAnyOrder("loadReviewFacts", "loadDebateContext");
        assertThat(registry.require(RoleType.SECURITY).activationRules()).isNotEmpty();
        assertThat(registry.all()).allSatisfy(rolePack -> {
            assertThat(rolePack.promptVersion()).matches("[a-z]+-v\\d+");
            assertThat(rolePack.maxIterations()).isBetween(1, 20);
        });
    }
}
