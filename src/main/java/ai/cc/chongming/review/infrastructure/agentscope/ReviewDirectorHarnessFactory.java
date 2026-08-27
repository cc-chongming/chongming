package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
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
    private final ReviewDebateToolFactory reviewDebateToolFactory;
    private final RequirementSnapshotStore requirementSnapshotStore;

    public ReviewDirectorHarnessFactory(
            ReviewWorkspaceLayout workspaceLayout,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ObjectProvider<DistributedStore> distributedStoreProvider) {
        this(workspaceLayout, modelGateway, agentScopeProperties, distributedStoreProvider, null, null);
    }

    public ReviewDirectorHarnessFactory(
            ReviewWorkspaceLayout workspaceLayout,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ObjectProvider<DistributedStore> distributedStoreProvider,
            ReviewDebateToolFactory reviewDebateToolFactory) {
        this(workspaceLayout, modelGateway, agentScopeProperties, distributedStoreProvider, reviewDebateToolFactory, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewDirectorHarnessFactory(
            ReviewWorkspaceLayout workspaceLayout,
            ModelGateway modelGateway,
            AgentScopeProperties agentScopeProperties,
            ObjectProvider<DistributedStore> distributedStoreProvider,
            ReviewDebateToolFactory reviewDebateToolFactory,
            RequirementSnapshotStore requirementSnapshotStore) {
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.agentScopeProperties = Objects.requireNonNull(agentScopeProperties, "agentScopeProperties must not be null");
        this.distributedStoreProvider = Objects.requireNonNull(distributedStoreProvider, "distributedStoreProvider must not be null");
        this.reviewDebateToolFactory = reviewDebateToolFactory;
        this.requirementSnapshotStore = requirementSnapshotStore;
    }

    /**
     * Builds the director with strong Plan Mode and no direct file, shell or autonomous subagent escape path.
     */
    public DirectorRuntime create(ReviewRuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        ReviewWorkspaceLayout.ReviewWorkspace workspace = workspaceLayout.open(runtimeContext);
        if (requirementSnapshotStore != null) {
            requirementSnapshotStore.materializeForAgentWorkspace(
                    runtimeContext.reviewId(), runtimeContext.attemptNo(), workspace.attempt(), runtimeContext.cancellation());
        }
        String prompt = directorPrompt();
        List<AgentTool> debateTools = reviewDebateToolFactory == null ? List.of() : reviewDebateToolFactory.directorTools(runtimeContext);
        Toolkit toolkit = new Toolkit();
        debateTools.forEach(toolkit::registerAgentTool);
        ScoutToolTraceCollector toolTraceCollector = new ScoutToolTraceCollector();
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(runtimeContext.directorLabel())
                .agentId(runtimeContext.directorLabel())
                .description("Coordinates an evidence-driven requirement review within ReviewProtocolGuard boundaries")
                .defaultSessionId(runtimeContext.directorSessionId())
                .workspace(workspace.attempt())
                .filesystem(attemptFilesystem(workspace))
                .model(new AgentScopeModelBridge(
                        modelGateway, runtimeContext, RoleType.DIRECTOR, "director", prompt,
                        debateTools.stream().map(AgentTool::getName).collect(java.util.stream.Collectors.toSet()),
                        toolTraceCollector::captureModelToolUse))
                .sysPrompt(prompt)
                .maxIters(agentScopeProperties.directorMaxIterations())
                .toolkit(toolkit)
                .enableTaskList()
                .enablePlanMode()
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
                .permissionContext(bypassPermissionContext());
        DistributedStore store = distributedStoreProvider.getIfAvailable();
        if (store != null) {
            builder.distributedStore(store);
        }
        if (!agentScopeProperties.persistSession()) {
            builder.disableSessionPersistence();
        }
        return new DirectorRuntime(runtimeContext, workspace, builder.build(), toolTraceCollector);
    }

    /**
     * Creates the protocol-only continuation used when a Director has read the authoritative
     * Claim inventory but ended before making the mandatory conflict-stage transition.
     */
    public DirectorFinalizerRuntime createNoConflictFinalizer(ReviewRuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        if (reviewDebateToolFactory == null) {
            throw new IllegalStateException("director conflict finalizer requires review debate tools");
        }
        List<AgentTool> finalizationTools = reviewDebateToolFactory.directorTools(runtimeContext).stream()
                .filter(tool -> tool.getName().equals("skip_debate_when_no_conflicts"))
                .toList();
        if (finalizationTools.size() != 1) {
            throw new IllegalStateException("director conflict finalizer requires exactly one no-conflict tool");
        }
        ReviewWorkspaceLayout.ReviewWorkspace workspace = workspaceLayout.open(runtimeContext);
        String prompt = "You are the ReviewDirectorHarness in conflict-finalization mode. Your conflict analysis ended "
                + "without a stage transition. Do not plan, read files, create facts, open topics, or return a text substitute. "
                + "The server has restricted you to one action: call skip_debate_when_no_conflicts now. "
                + "The server independently rejects it if conflicting persisted Claim positions exist. "
                + "Use Simplified Chinese for its public output.";
        Toolkit toolkit = new Toolkit();
        finalizationTools.forEach(toolkit::registerAgentTool);
        ScoutToolTraceCollector toolTraceCollector = new ScoutToolTraceCollector();
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(runtimeContext.directorLabel() + "-conflict-finalizer")
                .agentId(runtimeContext.directorLabel() + "-conflict-finalizer")
                .description("Protocol finalizer for a no-conflict Director transition")
                .defaultSessionId(runtimeContext.directorSessionId())
                .workspace(workspace.attempt())
                .filesystem(attemptFilesystem(workspace))
                .model(new AgentScopeModelBridge(
                        modelGateway, runtimeContext, RoleType.DIRECTOR, "director", prompt,
                        java.util.Set.of("skip_debate_when_no_conflicts"), toolTraceCollector::captureModelToolUse))
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
                .permissionContext(bypassPermissionContext());
        if (!agentScopeProperties.persistSession()) {
            builder.disableSessionPersistence();
        }
        return new DirectorFinalizerRuntime(builder.build(), toolTraceCollector);
    }

    /** Native Harness file tools must resolve from the attempt root, not the Spring process directory. */
    private static LocalFilesystemSpec attemptFilesystem(ReviewWorkspaceLayout.ReviewWorkspace workspace) {
        return new LocalFilesystemSpec()
                .project(workspace.attempt())
                .projectWritable(true)
                .mode(LocalFsMode.ROOTED)
                .addRoot(workspace.attempt());
    }

    private PermissionContextState bypassPermissionContext() {
        return PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .addDenyRule("shell", new PermissionRule("shell", "*", PermissionBehavior.DENY, "review-director-policy"))
                .addDenyRule("agent_spawn", new PermissionRule(
                        "agent_spawn", "*", PermissionBehavior.DENY, "review-director-policy"))
                .build();
    }

    private String directorPrompt() {
        return "You are ReviewDirectorHarness. Create and revise public review plans with plan_enter, plan_write, "
                + "and plan_exit, then request only "
                + "server-authorized role activation. "
                + "When you revise the plan, always use the plan mode flow: call plan_enter first, then plan_write to "
                + "rewrite plans/PLAN.md (include a dedicated line starting with 修订原因： followed by the reason, "
                + "and a task list using \"- \" markers), then plan_exit when the plan is settled. The server watches "
                + "plans/PLAN.md: every content change is promoted once into a public PLAN_REVISED event that updates "
                + "the review planning panel and its plan cards, so keep the document as the single source of the "
                + "public plan and do not leave draft files there. You do not decide final Gate results, bypass ReviewProtocolGuard, "
                + "read outside the current attempt workspace, run shell commands, access private role sessions, reveal hidden reasoning, "
                + "or create agents directly. During CONFLICT_DETECTION, call list_persisted_claims before opening debate topics; "
                + "it is the authoritative source for Claim IDs and is not materialized as a workspace file. "
                + "If any persisted Claim has an OPPOSE position, open debate topic(s) covering those conflicting Claims. "
                + "Prefer grouping related OPPOSE Claims into one topic and include a SUPPORT Claim when one exists for that subject, but when no SUPPORT Claim exists open the topic with the OPPOSE Claim(s) alone so the debate can still proceed. "
                + "[AIREVIEW-PLAN-033#1.1 revised] A topic without any SUPPORT claim still has an implicit defender: the requirement itself. For every such topic: "
                + "(1) dispatch DEFENSE to the PRODUCT role as the requirement defender: it must submit one SUPPORT claim for the topic via submit_claim carrying your DEFENSE command's commandId, stating the requirement position and its rationale; "
                + "(2) after that SUPPORT claim is persisted, dispatch CHALLENGE to each role owning an OPPOSE claim of the topic, targeting that new SUPPORT claim, so the objectors interrogate the requirement position — an objector that becomes convinced may revise or withdraw its objection via change_claim_position instead; "
                + "(3) the server itself issues the rebuttal envelope to the defender after each committed challenge; never re-issue it. "
                + "Never dispatch PRODUCT to challenge the objectors: the direction is objectors interrogate, defender answers. "
                + "If PRODUCT is not activated, appoint the activated role closest to the requirement as defender. Never let a debate topic run without a defending side. "
                + "Only when no persisted Claim has an OPPOSE position call skip_debate_when_no_conflicts instead of opening a topic. "
                + "[AIREVIEW-PLAN-024#方案3] You decide which topics open, who challenges whom, whether a second round happens and when to converge, "
                + "but you steer roles exclusively through dispatch_debate_action: issue exactly one directed dispatch command per intended write "
                + "action (recipientRole, allowedAction, topicId, and the target Claim or Turn). The server validates every command and delivers the "
                + "envelope only to the recipient role; never instruct roles with free text, never broadcast, and never grant an action beyond one command. "
                + "After CHALLENGE_SUBMITTED the server itself issues the rebuttal envelope to the challenged role; do not re-issue it. "
                + "When woken after a committed event, advance only through the registered debate stage tools. "
                + "When woken, perform at most ONE authoritative state read (list_persisted_claims or "
                + "list_persisted_debate_topics) before acting. Trust the tool result you just received: do not "
                + "re-read the same list to confirm a tool already succeeded, and do not re-read a list you already "
                + "fetched in the same wake. Take exactly one stage action per wake (close a topic, begin round two, "
                + "continue, or begin judging), then stop and wait for the next committed event. Do not call "
                + "todo_write to narrate what you are about to do; write a todo only when a server tool rejects an "
                + "action and you must track a recovery step. The persisted event stream is the public audit trail; "
                + "your todo list is not. "
                + "The current attempt workspace is the only filesystem root you may operate. Its input/requirement.md "
                + "is a mutable working copy of the uploaded requirement, and plans/ is available for planning artifacts. "
                + "All business facts must be submitted through strongly typed server tools. "
                + "Use Simplified Chinese for every visible response, plan, tool summary, and final text.";
    }

    /**
     * Director instance and all fixed workspace paths for one runtime attempt.
     *
     * @author wangli
     */
    public record DirectorRuntime(
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            HarnessAgent agent,
            ScoutToolTraceCollector toolTraceCollector) {
    }

    /** @author wangli */
    public record DirectorFinalizerRuntime(HarnessAgent agent, ScoutToolTraceCollector toolTraceCollector) {
    }
}
