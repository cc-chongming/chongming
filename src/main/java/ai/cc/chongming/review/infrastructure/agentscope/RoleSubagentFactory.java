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

    public RoleSubagentFactory(
            RolePackRegistry rolePackRegistry,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ReviewWorkspaceLayout workspaceLayout) {
        this.rolePackRegistry = Objects.requireNonNull(rolePackRegistry, "rolePackRegistry must not be null");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.agentScopeProperties = Objects.requireNonNull(agentScopeProperties, "agentScopeProperties must not be null");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
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
                .enablePlanMode()
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
                + " Return public " + rolePack.outputKind().name() + " JSON compatible output using prompt version "
                + rolePack.promptVersion() + ".";
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
