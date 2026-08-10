package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewContextAssembler;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewDebateToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewRepositoryToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewRoleToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.infrastructure.agentscope.RoleSubagentFactory;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrant;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrantSet;
import io.agentscope.core.tool.AgentTool;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
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

    /**
     * [AIREVIEW-PLAN-024] 方案1+2 joint contract: when a role's effective fileRef grant set is
     * empty, readLines/getFileMetadata are withdrawn by design, so the registered tool set is an
     * accepted subset of the declared RolePack set instead of a contract violation.
     */
    @Test
    void acceptsSubsetToolContractWhenReadToolsAreWithdrawnForAnEmptyGrantSet() {
        ReviewRuntimeContext context = runtimeContext();
        RoleSubagentFactory factory = contractFactory(
                List.of(toolNamed("searchText")), RepositoryFileGrantSet.empty());
        ReviewWorkspaceLayout workspaceLayout = workspaceLayout();

        RoleSubagentFactory.RoleRuntime product = factory.create(context, workspaceLayout.open(context), RoleType.PRODUCT);

        assertThat(product.rolePack().allowedTools()).contains("searchText", "readLines");
    }

    /**
     * [AIREVIEW-PLAN-024] 方案1+2 joint contract: declared tools may only be missing when the
     * read tools were withdrawn because the role has no granted repository files.
     */
    @Test
    void rejectsMissingDeclaredToolsWhenTheRoleStillHasGrantedFiles() {
        ReviewRuntimeContext context = runtimeContext();
        RepositoryFileGrantSet grants = RepositoryFileGrantSet.of(List.of(
                RepositoryFileGrant.issue(context.reviewId(), 1, RoleType.PRODUCT, "commit-001", "src/App.java")));
        RoleSubagentFactory factory = contractFactory(List.of(toolNamed("searchText")), grants);
        ReviewWorkspaceLayout workspaceLayout = workspaceLayout();

        assertThatThrownBy(() -> factory.create(context, workspaceLayout.open(context), RoleType.PRODUCT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool contract mismatch");
    }

    /**
     * [AIREVIEW-PLAN-024] 方案1+2 joint contract: registered tools must always be a subset of the
     * declared RolePack tools, even when the grant set is empty.
     */
    @Test
    void rejectsUndeclaredToolsEvenWhenTheGrantSetIsEmpty() {
        ReviewRuntimeContext context = runtimeContext();
        RoleSubagentFactory factory = contractFactory(
                List.of(toolNamed("searchText"), toolNamed("rogue_host_tool")), RepositoryFileGrantSet.empty());
        ReviewWorkspaceLayout workspaceLayout = workspaceLayout();

        assertThatThrownBy(() -> factory.create(context, workspaceLayout.open(context), RoleType.PRODUCT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not declared");
    }

    private ReviewRuntimeContext runtimeContext() {
        return new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "user-001", "trace-001", IntakeCancellation.neverCancelled());
    }

    private ReviewWorkspaceLayout workspaceLayout() {
        return new ReviewWorkspaceLayout(
                new ReviewProperties(temporaryDirectory.toString(), 8, 2),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private RoleSubagentFactory contractFactory(List<AgentTool> repositoryTools, RepositoryFileGrantSet grants) {
        List<AgentTool> initialReviewTools = List.of(
                toolNamed("submit_assessment"), toolNamed("submit_claim"),
                toolNamed("complete_initial_review"));
        List<AgentTool> debateRoleTools = List.of(
                toolNamed("list_persisted_debate_topics"), toolNamed("submit_challenge"),
                toolNamed("submit_rebuttal"), toolNamed("change_claim_position"),
                toolNamed("request_additional_evidence"));
        ReviewRoleToolFactory roleToolFactory = Mockito.mock(ReviewRoleToolFactory.class);
        Mockito.when(roleToolFactory.initialReviewTools(Mockito.any(), Mockito.any()))
                .thenReturn(initialReviewTools);
        ReviewDebateToolFactory debateToolFactory = Mockito.mock(ReviewDebateToolFactory.class);
        Mockito.when(debateToolFactory.roleTools(Mockito.any(), Mockito.any()))
                .thenReturn(debateRoleTools);
        ReviewRepositoryToolFactory repositoryToolFactory = Mockito.mock(ReviewRepositoryToolFactory.class);
        Mockito.when(repositoryToolFactory.readTools(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(repositoryTools);
        Mockito.when(repositoryToolFactory.roleFileGrants(Mockito.any(), Mockito.any()))
                .thenReturn(grants);
        Mockito.when(repositoryToolFactory.rolePublicContext(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn("");
        return new RoleSubagentFactory(
                new RolePackRegistry(new PathMatchingResourcePatternResolver()),
                unavailableGateway(),
                new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString()),
                workspaceLayout(),
                roleToolFactory,
                debateToolFactory,
                repositoryToolFactory,
                Mockito.mock(ReviewContextAssembler.class),
                null);
    }

    private AgentTool toolNamed(String name) {
        AgentTool tool = Mockito.mock(AgentTool.class);
        Mockito.when(tool.getName()).thenReturn(name);
        return tool;
    }

    private ModelGateway unavailableGateway() {
        return (request, cancellation) -> Mono.error(new AssertionError("model execution is outside factory construction test"));
    }
}
