package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-018#3.3] Creates the non-voting Context Scout Harness that prepares a bounded repository overview.
 *
 * @author wangli
 */
@Component
public class ContextScoutHarnessFactory {

    private static final String SCOUT_LABEL = "CONTEXT_SCOUT";

    private final ModelGateway modelGateway;
    private final AgentScopeProperties properties;
    private final ReviewRepositoryToolFactory repositoryToolFactory;
    private final ObjectMapper objectMapper;
    private final ReviewWorkspaceLayout workspaceLayout;

    public ContextScoutHarnessFactory(
            ModelGateway modelGateway,
            AgentScopeProperties properties,
            ReviewRepositoryToolFactory repositoryToolFactory,
            ObjectMapper objectMapper,
            ReviewWorkspaceLayout workspaceLayout) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.repositoryToolFactory = Objects.requireNonNull(repositoryToolFactory, "repositoryToolFactory must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
    }

    /** Creates one attempt-local, read-only scout without Claim, debate or Gate capabilities. */
    public HarnessAgent create(ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        List<AgentTool> tools = repositoryToolFactory.scoutReadTools(context);
        Toolkit toolkit = new Toolkit();
        tools.forEach(toolkit::registerAgentTool);
        ReviewRepositoryToolFactory.SharedProjectContext overview = repositoryToolFactory.sharedProjectContext(context);
        writeBaselineArtifact(context, workspace, overview);
        String prompt = prompt(overview);
        return HarnessAgent.builder()
                .name(SCOUT_LABEL)
                .agentId(SCOUT_LABEL)
                .description("Prepares a bounded public repository overview before review role activation")
                .defaultSessionId(context.runtimeId() + ":context-scout")
                .workspace(workspace.attempt())
                .model(new AgentScopeModelBridge(
                        modelGateway,
                        context,
                        RoleType.DIRECTOR,
                        "scout",
                        prompt,
                        tools.stream().map(AgentTool::getName).collect(Collectors.toUnmodifiableSet())))
                .sysPrompt(prompt)
                .maxIters(Math.min(properties.directorMaxIterations(), 16))
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
                .permissionContext(readOnlyPermissions())
                .build();
    }

    private static PermissionContextState readOnlyPermissions() {
        return PermissionContextState.builder()
                .mode(PermissionMode.EXPLORE)
                .addDenyRule("shell", new PermissionRule("shell", "*", PermissionBehavior.DENY, "context-scout-policy"))
                .addDenyRule("filesystem", new PermissionRule(
                        "filesystem", "*", PermissionBehavior.DENY, "context-scout-policy"))
                .build();
    }

    private void writeBaselineArtifact(
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            ReviewRepositoryToolFactory.SharedProjectContext overview) {
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "schemaVersion", 1,
                    "repositoryId", overview.repositoryId(),
                    "headCommit", overview.headCommit(),
                    "moduleRoots", overview.moduleRoots(),
                    "evidencePaths", overview.sampleFiles(),
                    "roleScopes", java.util.Map.of(
                            "PRODUCT", List.of("README.md", "docs/"),
                            "PROJECT", List.of("README.md", "docs/", "pom.xml", "package.json"),
                            "FRONTEND", List.of("frontend/", "web/", "ui/", "client/"),
                            "BACKEND", List.of("src/main/", "src/test/", "backend/", "server/", "service/", "api/"),
                            "TESTING", List.of("src/test/", "test/", "tests/"))));
            workspaceLayout.writeArtifact(
                    workspace,
                    ReviewWorkspaceLayout.ArtifactArea.PLANS,
                    "context-scout-baseline.json",
                    payload,
                    context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Context Scout baseline", exception);
        }
    }

    private static String prompt(ReviewRepositoryToolFactory.SharedProjectContext overview) {
        return "你是 Context Scout。你不是评审角色，不提交 Claim、不参与辩论、不决定 Gate。"
                + "只可使用服务器提供的只读快照工具，先根据以下需求和仓库概览做最少必要的核验，"
                + "识别与需求有关的模块、入口文件、构建方式与风险边界。不得访问宿主文件、Shell、角色会话或隐藏推理。"
                + "完成后仅用简体中文输出 JSON：summary、moduleRoots、entryPoints、risks、evidencePaths、roleScopes 六个字段；"
                + "每项结论必须列出已读取的快照相对路径。\n\n"
                + overview.publicText(RoleType.PROJECT);
    }

    /** Persists the Scout's final visible response as a public, integrity-protected workspace artifact. */
    public void recordResult(
            ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace, String visibleResult) {
        repositoryToolFactory.recordScoutResult(context, visibleResult);
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "schemaVersion", 1,
                    "agentId", SCOUT_LABEL,
                    "visibleResult", visibleResult));
            workspaceLayout.writeArtifact(
                    workspace, ReviewWorkspaceLayout.ArtifactArea.PLANS, "context-scout-result.json", payload, context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Context Scout result", exception);
        }
    }
}
