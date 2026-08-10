package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.ReviewContextAssembler;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
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
    private final ReviewDebateToolFactory reviewDebateToolFactory;
    private final ReviewRepositoryToolFactory reviewRepositoryToolFactory;
    private final ReviewContextAssembler reviewContextAssembler;

    public RoleSubagentFactory(
            RolePackRegistry rolePackRegistry,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ReviewWorkspaceLayout workspaceLayout) {
        this(rolePackRegistry, modelGateway, agentScopeProperties, workspaceLayout, null, null, null, null);
    }

    public RoleSubagentFactory(
            RolePackRegistry rolePackRegistry,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ReviewWorkspaceLayout workspaceLayout,
            ReviewRoleToolFactory reviewRoleToolFactory,
            ReviewDebateToolFactory reviewDebateToolFactory) {
        this(rolePackRegistry, modelGateway, agentScopeProperties, workspaceLayout, reviewRoleToolFactory,
                reviewDebateToolFactory, null, null);
    }

    public RoleSubagentFactory(
            RolePackRegistry rolePackRegistry,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ReviewWorkspaceLayout workspaceLayout,
            ReviewRoleToolFactory reviewRoleToolFactory,
            ReviewDebateToolFactory reviewDebateToolFactory,
            ReviewRepositoryToolFactory reviewRepositoryToolFactory) {
        this(rolePackRegistry, modelGateway, agentScopeProperties, workspaceLayout, reviewRoleToolFactory,
                reviewDebateToolFactory, reviewRepositoryToolFactory, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RoleSubagentFactory(
            RolePackRegistry rolePackRegistry,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ReviewWorkspaceLayout workspaceLayout,
            ReviewRoleToolFactory reviewRoleToolFactory,
            ReviewDebateToolFactory reviewDebateToolFactory,
            ReviewRepositoryToolFactory reviewRepositoryToolFactory,
            ReviewContextAssembler reviewContextAssembler) {
        this.rolePackRegistry = Objects.requireNonNull(rolePackRegistry, "rolePackRegistry must not be null");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.agentScopeProperties = Objects.requireNonNull(agentScopeProperties, "agentScopeProperties must not be null");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
        this.reviewRoleToolFactory = reviewRoleToolFactory;
        this.reviewDebateToolFactory = reviewDebateToolFactory;
        this.reviewRepositoryToolFactory = reviewRepositoryToolFactory;
        this.reviewContextAssembler = reviewContextAssembler;
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
        String publicContext = reviewRepositoryToolFactory == null || roleType == RoleType.JUDGE
                ? ""
                : reviewContextAssembler == null
                        ? reviewRepositoryToolFactory.sharedProjectContext(runtimeContext).publicText(roleType)
                        : reviewRepositoryToolFactory.rolePublicContext(runtimeContext, rolePack, reviewContextAssembler);
        String prompt = rolePrompt(rolePack, publicContext);
        Toolkit toolkit = reviewToolkit(runtimeContext, roleType, rolePack.allowedTools());
        ScoutToolTraceCollector toolTraceCollector = new ScoutToolTraceCollector();
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
                        rolePack.allowedTools(),
                        toolTraceCollector::captureModelToolUse))
                .sysPrompt(prompt)
                .maxIters(rolePack.maxIterations())
                .toolkit(toolkit)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
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
                builder.build(),
                toolTraceCollector);
    }

    /**
     * Creates the protocol-only continuation used after a bounded initial-review investigation
     * reaches its iteration budget. It deliberately omits every repository and debate tool.
     */
    public RoleFinalizerRuntime createInitialReviewFinalizer(
            ReviewRuntimeContext runtimeContext, RoleRuntime roleRuntime) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(roleRuntime, "roleRuntime must not be null");
        RolePack rolePack = roleRuntime.rolePack();
        if (!rolePack.allowedTools().contains("complete_initial_review")) {
            throw new IllegalArgumentException("role does not require initial-review completion: " + rolePack.roleType());
        }
        java.util.Set<String> finalizationTools = java.util.Set.of("complete_initial_review");
        String publicContext = reviewRepositoryToolFactory == null
                ? ""
                : reviewContextAssembler == null
                        ? reviewRepositoryToolFactory.sharedProjectContext(runtimeContext).publicText(rolePack.roleType())
                        : reviewRepositoryToolFactory.rolePublicContext(runtimeContext, rolePack, reviewContextAssembler);
        String prompt = finalizationPrompt(rolePack, publicContext);
        Toolkit toolkit = reviewToolkit(runtimeContext, rolePack.roleType(), finalizationTools);
        ScoutToolTraceCollector toolTraceCollector = new ScoutToolTraceCollector();
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(roleRuntime.label() + "-finalizer")
                .agentId(roleRuntime.label() + "-finalizer")
                .description("Protocol finalizer for " + rolePack.roleType())
                .defaultSessionId(roleRuntime.sessionId())
                .workspace(roleRuntime.workspace())
                .model(new AgentScopeModelBridge(
                        modelGateway,
                        runtimeContext,
                        rolePack.roleType(),
                        rolePack.modelProfile(),
                        prompt,
                        finalizationTools,
                        toolTraceCollector::captureModelToolUse))
                .sysPrompt(prompt)
                .maxIters(4)
                .toolkit(toolkit)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .skillsEnabled(false)
                .middleware(toolTraceCollector)
                .permissionContext(readOnlyPermissionContext());
        if (!agentScopeProperties.persistSession()) {
            builder.disableSessionPersistence();
        }
        return new RoleFinalizerRuntime(builder.build(), toolTraceCollector);
    }

    private PermissionContextState readOnlyPermissionContext() {
        return PermissionContextState.builder()
                .mode(PermissionMode.EXPLORE)
                .addDenyRule("shell", new PermissionRule("shell", "*", PermissionBehavior.DENY, "review-role-policy"))
                .addDenyRule("filesystem", new PermissionRule(
                        "filesystem", "*", PermissionBehavior.DENY, "review-role-policy"))
                .build();
    }

    private String rolePrompt(RolePack rolePack, String publicContext) {
        if (rolePack.roleType() == RoleType.JUDGE) {
            return "You are the JUDGE role. Remain idle until the server sends a JUDGING-stage instruction. "
                    + "Then first call list_persisted_debate_topics. If terminal topics exist, use submit_judgement for every one of them. "
                    + "Always finish by calling draft_gate exactly once, even when the topic list is empty: the Gate must be drafted from the persisted Claims alone in that case. "
                    + "Never end the judging stage idle without calling draft_gate. "
                    + "Never create Claims, Evidence, or a final human Gate. "
                    + "Use Simplified Chinese for every visible response, judgement summary, tool summary, and final text.";
        }
        String repositoryGuidance = rolePack.allowedTools().contains("listFiles")
                ? "Use listFiles only when the supplied project overview is insufficient, then use targeted searchText, readLines, or findSymbol "
                : "Do not call listFiles because it is not authorized for this role; use the supplied project overview and targeted searchText or readLines "
                        + "only when repository evidence is necessary ";
        String completionGuidance = rolePack.allowedTools().contains("complete_initial_review")
                ? "Submit every finding with submit_claim, then always call complete_initial_review, including when there are no findings. "
                : "Submit only the evidence and formal actions authorized for this role; do not claim that an unavailable completion tool was called. ";
        String checklistGuidance = rolePack.checklist().isEmpty()
                ? ""
                : "You must explicitly cover these review checkpoints: "
                        + rolePack.checklist().stream()
                                .map(checkpoint -> checkpoint.hasStableKey()
                                        ? checkpoint.checkpointKey() + " (" + checkpoint.instruction() + ")"
                                        : checkpoint.instruction())
                                .collect(Collectors.joining("; "))
                        + ". ";
        String iterationGuidance = rolePack.allowedTools().contains("complete_initial_review")
                ? "Your maximum is " + rolePack.maxIterations() + " model turns. Spend at most the first twelve turns "
                        + "on repository investigation; reserve the remaining turns for submit_claim and complete_initial_review. "
                        + "When the evidence is sufficient or the investigation budget is reached, stop repository reads immediately. "
                : "";
        return "You are the " + rolePack.roleType().name() + " review role. "
                + rolePack.description()
                + " Work only from the supplied public context and server-authorized tool results. "
                + " Read repository files only through server-authorized snapshot tools; never access host files, shell commands, "
                + "other role sessions, hidden reasoning, credentials, or final Gate authority. "
                + workflowGuidance(rolePack.roleType())
                + "During a debate-stage instruction, first call list_persisted_debate_topics before using a debate turn tool. "
                + checklistGuidance
                + " " + repositoryGuidance
                + "for concrete files and symbols. Do not probe directories or read repository documents outside the assigned review. "
                + iterationGuidance
                + completionGuidance
                + " Do not treat final text as a substitute for either tool. Return public "
                + rolePack.outputKind().name() + " JSON compatible output using prompt version " + rolePack.promptVersion() + ". "
                + "Use Simplified Chinese for every visible response, claim summary, tool summary, and final text.\n\n"
                + publicContext;
    }

    /**
     * ECC-derived review workflows are adapted to evidence collection only; they never grant the
     * original coding-agent's host, shell, edit, deployment or secret access.
     */
    private static String workflowGuidance(RoleType roleType) {
        return switch (roleType) {
            case DIRECTOR -> "Follow the review-director workflow: keep the public plan and role hand-offs coherent, route only verified evidence "
                    + "to the appropriate stage, and never substitute an agent narrative for a protocol transition. ";
            case PRODUCT -> "Follow the Product Lens workflow: identify target users and pain, trace the critical user journey, "
                    + "separate MVP scope from anti-goals, and turn ambiguities into acceptance risks. ";
            case PROJECT -> "Follow the Code Architect workflow: map dependencies and delivery sequence, identify ownership and integration boundaries, "
                    + "then assess whether milestones have executable acceptance conditions. ";
            case ARCHITECTURE -> "Follow the Code Architect workflow: trace entry points and call paths, map component boundaries and dependency direction, "
                    + "then report only concrete extensibility, resilience or coupling risks. ";
            case BACKEND -> "Follow the Spring Boot service-review workflow: trace request-to-data flow, validate contracts, consistency, failure handling, "
                    + "observability and operational rollback boundaries. ";
            case FRONTEND -> "Follow the frontend review workflow: trace user actions through UI state and API contracts, then check accessibility, loading, error "
                    + "and recovery states. ";
            case TESTING -> "Follow the TDD and E2E workflow: derive happy paths, boundaries and regressions from acceptance criteria, then identify the smallest "
                    + "deterministic unit, integration and end-to-end evidence required. ";
            case PERFORMANCE -> "Follow the performance review workflow: identify critical request paths, data volume assumptions, synchronous fan-out, hot queries, "
                    + "resource ceilings and observable load-test acceptance thresholds. ";
            case SECURITY -> "Follow the security-review workflow: establish trust boundaries, validate authorization and input handling, then inspect sensitive data, "
                    + "injection and external-exposure risks. ";
            case JUDGE -> "Follow the evidence-review workflow: compare only persisted claims and cited evidence, distinguish unresolved uncertainty from a proven defect, "
                    + "and state whether a human decision is necessary. ";
        };
    }

    private String finalizationPrompt(RolePack rolePack, String publicContext) {
        return "You are the " + rolePack.roleType().name() + " review role in protocol-finalization mode. "
                + "The bounded investigation phase has ended. Do not investigate, read files, debate, request context, or submit new Claims. "
                + "Your only authorized action is complete_initial_review. Call it now with a concise public summary, including when there are no findings. "
                + "Use Simplified Chinese for every visible response, claim summary, tool summary, and final text.\n\n"
                + publicContext;
    }

    private Toolkit reviewToolkit(
            ReviewRuntimeContext runtimeContext, RoleType roleType, java.util.Set<String> allowedToolNames) {
        Toolkit toolkit = new Toolkit();
        List<io.agentscope.core.tool.AgentTool> tools = new ArrayList<>();
        if (roleType != RoleType.JUDGE && reviewRoleToolFactory != null) {
            addAllowed(tools, reviewRoleToolFactory.initialReviewTools(runtimeContext, roleType), allowedToolNames);
        }
        if (reviewDebateToolFactory != null) {
            addAllowed(tools, roleType == RoleType.JUDGE
                    ? reviewDebateToolFactory.judgeTools(runtimeContext)
                    : reviewDebateToolFactory.roleTools(runtimeContext, roleType), allowedToolNames);
        }
        if (reviewRepositoryToolFactory != null && roleType != RoleType.JUDGE) {
            tools.addAll(reviewRepositoryToolFactory.readTools(runtimeContext, roleType, allowedToolNames));
        }
        if (reviewRoleToolFactory != null && reviewDebateToolFactory != null && reviewRepositoryToolFactory != null) {
            assertToolContract(roleType, allowedToolNames, tools);
        }
        tools.forEach(toolkit::registerAgentTool);
        return toolkit;
    }

    private void addAllowed(
            List<io.agentscope.core.tool.AgentTool> target,
            List<io.agentscope.core.tool.AgentTool> candidates,
            java.util.Set<String> allowedToolNames) {
        candidates.stream()
                .filter(tool -> allowedToolNames.contains(tool.getName()))
                .forEach(target::add);
    }

    private void assertToolContract(
            RoleType roleType,
            java.util.Set<String> declared,
            List<io.agentscope.core.tool.AgentTool> tools) {
        java.util.Set<String> registered = tools.stream()
                .map(io.agentscope.core.tool.AgentTool::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!registered.equals(declared)) {
            throw new IllegalStateException("RolePack tool contract mismatch for " + roleType
                    + ": declared=" + declared + ", registered=" + registered);
        }
    }

    /** Releases attempt-local snapshot and public-context references after runtime cancellation. */
    public void release(ReviewRuntimeContext runtimeContext) {
        if (reviewRepositoryToolFactory != null) {
            reviewRepositoryToolFactory.release(runtimeContext);
        }
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
            HarnessAgent agent,
            ScoutToolTraceCollector toolTraceCollector) {
    }

    /** Runtime resources for the protocol-only completion continuation. */
    public record RoleFinalizerRuntime(HarnessAgent agent, ScoutToolTraceCollector toolTraceCollector) {
    }
}
