package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Creates the single director Harness for an attempt with project-controlled plan and subagent boundaries.
 *
 * @author wangli
 */
@Component
public class ReviewDirectorHarnessFactory {

    private final ReviewWorkspaceLayout workspaceLayout;
    private final ModelGateway modelGateway;
    private final AgentScopeProperties agentScopeProperties;
    private final ObjectProvider<DistributedStore> distributedStoreProvider;

    public ReviewDirectorHarnessFactory(
            ReviewWorkspaceLayout workspaceLayout,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ObjectProvider<DistributedStore> distributedStoreProvider) {
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.agentScopeProperties = Objects.requireNonNull(agentScopeProperties, "agentScopeProperties must not be null");
        this.distributedStoreProvider = Objects.requireNonNull(distributedStoreProvider, "distributedStoreProvider must not be null");
    }

    /**
     * Builds the director with strong Plan Mode and no direct file, shell or autonomous subagent escape path.
     */
    public DirectorRuntime create(ReviewRuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        ReviewWorkspaceLayout.ReviewWorkspace workspace = workspaceLayout.open(runtimeContext);
        String prompt = directorPrompt();
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(runtimeContext.directorLabel())
                .agentId(runtimeContext.directorLabel())
                .description("Coordinates an evidence-driven requirement review within ReviewProtocolGuard boundaries")
                .defaultSessionId(runtimeContext.directorSessionId())
                .workspace(workspace.attempt())
                .model(new AgentScopeModelBridge(
                        modelGateway, runtimeContext, RoleType.DIRECTOR, "director", prompt, java.util.Set.of()))
                .sysPrompt(prompt)
                .maxIters(12)
                .enablePlanMode()
                .enableTaskList()
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .skillsEnabled(false)
                .permissionContext(readOnlyPermissionContext());
        DistributedStore store = distributedStoreProvider.getIfAvailable();
        if (store != null) {
            builder.distributedStore(store);
        }
        if (!agentScopeProperties.persistSession()) {
            builder.disableSessionPersistence();
        }
        return new DirectorRuntime(runtimeContext, workspace, builder.build());
    }

    private PermissionContextState readOnlyPermissionContext() {
        return PermissionContextState.builder()
                .mode(PermissionMode.EXPLORE)
                .addDenyRule("shell", new PermissionRule("shell", "*", PermissionBehavior.DENY, "review-director-policy"))
                .addDenyRule("filesystem", new PermissionRule(
                        "filesystem", "*", PermissionBehavior.DENY, "review-director-policy"))
                .addDenyRule("agent_spawn", new PermissionRule(
                        "agent_spawn", "*", PermissionBehavior.DENY, "review-director-policy"))
                .build();
    }

    private String directorPrompt() {
        return "You are ReviewDirectorHarness. Create and revise public review plans, then request only "
                + "server-authorized role activation. You do not decide final Gate results, bypass ReviewProtocolGuard, "
                + "read arbitrary files, run shell commands, access private role sessions, reveal hidden reasoning, "
                + "or create agents directly. All business facts must be submitted through strongly typed server tools.";
    }

    /**
     * Director instance and all fixed workspace paths for one runtime attempt.
     *
     * @author wangli
     */
    public record DirectorRuntime(
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            HarnessAgent agent) {
    }
}
