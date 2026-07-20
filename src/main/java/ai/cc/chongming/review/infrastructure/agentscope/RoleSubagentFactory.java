package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Builds isolated, persistent role Harness instances rather than relying on parent policy propagation.
 *
 * @author wangli
 */
@Component
public class RoleSubagentFactory {

    private final RolePackRegistry rolePackRegistry;
    private final ModelGateway modelGateway;
    private final AgentScopeProperties agentScopeProperties;
    private final ReviewWorkspaceLayout workspaceLayout;
    private final ReviewRoleToolFactory reviewRoleToolFactory;

    public RoleSubagentFactory(
            RolePackRegistry rolePackRegistry,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ReviewWorkspaceLayout workspaceLayout) {
        this(rolePackRegistry, modelGateway, agentScopeProperties, workspaceLayout, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RoleSubagentFactory(
            RolePackRegistry rolePackRegistry,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ReviewWorkspaceLayout workspaceLayout,
            ReviewRoleToolFactory reviewRoleToolFactory) {
        this.rolePackRegistry = Objects.requireNonNull(rolePackRegistry, "rolePackRegistry must not be null");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.agentScopeProperties = Objects.requireNonNull(agentScopeProperties, "agentScopeProperties must not be null");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
        this.reviewRoleToolFactory = reviewRoleToolFactory;
    }

    /**
     * Creates a role worker with an independent session, workspace and explicit read-only policy.
     */
    public RoleRuntime create(
            ReviewRuntimeContext runtimeContext, ReviewWorkspaceLayout.ReviewWorkspace workspace, RoleType roleType) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        if (roleType == RoleType.DIRECTOR) {
            throw new IllegalArgumentException("director is created by ReviewDirectorHarnessFactory");
        }
        RolePack rolePack = rolePackRegistry.require(roleType);
        Path roleWorkspace = workspaceLayout.roleWorkspace(workspace, roleType);
        String prompt = rolePrompt(rolePack);
        Toolkit toolkit = reviewToolkit(runtimeContext, roleType);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(runtimeContext.roleLabel(roleType))
                .agentId(runtimeContext.roleLabel(roleType))
                .description(rolePack.description())
                .defaultSessionId(runtimeContext.roleSessionId(roleType))
                .workspace(roleWorkspace)
                .model(new AgentScopeModelBridge(
                        modelGateway,
                        runtimeContext,
                        roleType,
                        rolePack.modelProfile(),
                        prompt,
                        rolePack.allowedTools()))
                .sysPrompt(prompt)
                .maxIters(rolePack.maxIterations())
                .toolkit(toolkit)
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
        if (!agentScopeProperties.persistSession()) {
            builder.disableSessionPersistence();
        }
        return new RoleRuntime(
                roleType,
                runtimeContext.roleLabel(roleType),
                runtimeContext.roleSessionId(roleType),
                rolePack,
                roleWorkspace,
                builder.build());
    }

    private PermissionContextState readOnlyPermissionContext() {
        return PermissionContextState.builder()
                .mode(PermissionMode.EXPLORE)
                .addDenyRule("shell", new PermissionRule("shell", "*", PermissionBehavior.DENY, "review-role-policy"))
                .addDenyRule("filesystem", new PermissionRule(
                        "filesystem", "*", PermissionBehavior.DENY, "review-role-policy"))
                .build();
    }

    private String rolePrompt(RolePack rolePack) {
        return "You are the " + rolePack.roleType().name() + " review role. "
                + rolePack.description()
                + " Work only from the supplied public context and server-authorized tool results. "
                + " Do not access files, shell commands, other role sessions, hidden reasoning, credentials, or final Gate authority. "
                + " Submit every finding with submit_claim, then always call complete_initial_review, including when there are no findings. "
                + " Do not treat final text as a substitute for either tool. Return public "
                + rolePack.outputKind().name() + " JSON compatible output using prompt version " + rolePack.promptVersion() + ".";
    }

    private Toolkit reviewToolkit(ReviewRuntimeContext runtimeContext, RoleType roleType) {
        Toolkit toolkit = new Toolkit();
        if (reviewRoleToolFactory != null) {
            reviewRoleToolFactory.initialReviewTools(runtimeContext, roleType).forEach(toolkit::registerAgentTool);
        }
        return toolkit;
    }

    /**
     * Isolated role agent identity and its fixed capability declaration.
     *
     * @author wangli
     */
    public record RoleRuntime(
            RoleType roleType,
            String label,
            String sessionId,
            RolePack rolePack,
            Path workspace,
            HarnessAgent agent) {
    }
}
