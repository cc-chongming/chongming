package ai.cc.chongming.review.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePack.Checkpoint;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    @Test
    void givesEveryCoreRoleAtLeastOneRequiredCheckpoint() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());

        for (RoleType coreRole : List.of(RoleType.PRODUCT, RoleType.PROJECT, RoleType.FRONTEND, RoleType.BACKEND)) {
            RolePack rolePack = registry.require(coreRole);
            assertThat(rolePack.checklist())
                    .as("core role %s must declare at least one required checkpoint", coreRole)
                    .anyMatch(Checkpoint::required);
        }
    }

    @Test
    void exposesStableGloballyUniqueCheckpointKeysForCoreRoles() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());

        Set<String> seenKeys = new HashSet<>();
        for (RoleType coreRole : List.of(RoleType.PRODUCT, RoleType.PROJECT, RoleType.FRONTEND, RoleType.BACKEND)) {
            for (Checkpoint checkpoint : registry.require(coreRole).checklist()) {
                assertThat(checkpoint.hasStableKey())
                        .as("core role %s checkpoint must have a stable key", coreRole)
                        .isTrue();
                assertThat(checkpoint.checkpointKey())
                        .as("core role %s checkpointKey must be lower snake-case", coreRole)
                        .matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]+)*");
                assertThat(seenKeys.add(checkpoint.checkpointKey()))
                        .as("checkpointKey must be globally unique: %s", checkpoint.checkpointKey())
                        .isTrue();
            }
        }
    }

    @Test
    void givesEveryRequiredCheckpointANonBlankInstruction() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());

        assertThat(registry.all())
                .flatExtracting(RolePack::checklist)
                .filteredOn(Checkpoint::required)
                .isNotEmpty()
                .allSatisfy(checkpoint -> assertThat(checkpoint.instruction()).isNotBlank());
    }

    @Test
    void keepsLegacyTextCheckpointsForOptionalRolesWhileRejectingInvalidCheckpoints() {
        RolePackRegistry registry = new RolePackRegistry(new PathMatchingResourcePatternResolver());

        assertThat(registry.require(RoleType.SECURITY).checklist())
                .allSatisfy(checkpoint -> assertThat(checkpoint.hasStableKey()).isFalse());

        Checkpoint legacy = new Checkpoint(null, "Legacy plain-text checkpoint", false);
        assertThat(legacy.hasStableKey()).isFalse();
        assertThatThrownBy(() -> new Checkpoint("backend.api_contract", " ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instruction");
        assertThatThrownBy(() -> new Checkpoint("Backend.Api_Contract", "Verify API", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkpointKey");
    }

    @Test
    void rejectsInvalidRolePackShape() {
        assertThatThrownBy(() -> pack(" ", Duration.ofSeconds(30), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
        assertThatThrownBy(() -> pack("Backend reviewer", Duration.ZERO, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        assertThatThrownBy(() -> pack("Backend reviewer", Duration.ofSeconds(30), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations");
    }

    private RolePack pack(String description, Duration timeout, int maxIterations) {
        return new RolePack(
                RoleType.BACKEND,
                description,
                List.of("Always"),
                "backend-v1",
                Set.of("requirement-snapshot"),
                List.of(new Checkpoint("backend.api_contract", "Check API", true)),
                Set.of("searchText"),
                Kind.ROLE_ASSESSMENT,
                "role-reviewer",
                timeout,
                maxIterations);
    }
}
