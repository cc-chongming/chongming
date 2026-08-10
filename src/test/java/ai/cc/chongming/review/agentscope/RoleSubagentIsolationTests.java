package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.infrastructure.agentscope.RoleSubagentFactory;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies each role receives an isolated agent, session, workspace and fixed capability declaration.
 *
 * @author wangli
 */
class RoleSubagentIsolationTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsIndependentStableRoleRuntimeAndRejectsDirectorAsChild() {
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(reviewProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        RoleSubagentFactory factory = new RoleSubagentFactory(
                new RolePackRegistry(new PathMatchingResourcePatternResolver()),
                unavailableGateway(),
                new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString()),
                workspaceLayout);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "user-001", "trace-001", IntakeCancellation.neverCancelled());

        RoleSubagentFactory.RoleRuntime product = factory.create(context, workspaceLayout.open(context), RoleType.PRODUCT);

        assertThat(product.label()).isEqualTo(context.roleLabel(RoleType.PRODUCT));
        assertThat(product.sessionId()).isEqualTo(context.roleSessionId(RoleType.PRODUCT));
        assertThat(product.workspace()).isNotEqualTo(workspaceLayout.open(context).attempt());
        assertThat(product.rolePack().allowedTools())
                .contains("searchText", "submit_assessment", "submit_claim", "complete_initial_review")
                .doesNotContain("submitEvidence");
        assertThatThrownBy(() -> factory.create(context, workspaceLayout.open(context), RoleType.DIRECTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("director");
    }

    private ModelGateway unavailableGateway() {
        return (request, cancellation) -> Mono.error(new AssertionError("model execution is outside factory construction test"));
    }
}
