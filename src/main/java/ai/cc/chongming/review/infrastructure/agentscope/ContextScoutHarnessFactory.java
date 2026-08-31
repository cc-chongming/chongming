package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.ContextScoutConclusionService;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-018#3.3][AIREVIEW-PLAN-023#5] Creates the non-voting Context Scout Harness that prepares a bounded repository overview.
 *
 * @author zyj
 */
@Component
public class ContextScoutHarnessFactory {

    private static final String SCOUT_LABEL = "CONTEXT_SCOUT";
    private static final Set<String> INIT_RETRIEVAL_TOOLS = Set.of("glob_files", "grep_files", "read_file");

    private final ModelGateway modelGateway;
    private final AgentScopeProperties properties;
    private final ReviewRepositoryToolFactory repositoryToolFactory;
    private final ObjectMapper objectMapper;
    private final ReviewWorkspaceLayout workspaceLayout;
    private final ContextScoutConclusionService conclusionService;

    public ContextScoutHarnessFactory(
            ModelGateway modelGateway,
            AgentScopeProperties properties,
            ReviewRepositoryToolFactory repositoryToolFactory,
            ObjectMapper objectMapper,
            ReviewWorkspaceLayout workspaceLayout,
            ContextScoutConclusionService conclusionService) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.repositoryToolFactory = Objects.requireNonNull(repositoryToolFactory, "repositoryToolFactory must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
        this.conclusionService = Objects.requireNonNull(conclusionService, "conclusionService must not be null");
    }

    /** Creates one attempt-local, read-only scout without Claim, debate or Gate capabilities. */
    public HarnessAgent create(ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace) {
        return createRuntime(context, workspace).agent();
    }

    /** Creates the non-voting Scout together with its local runtime tool transcript. */
    public ScoutRuntime createRuntime(ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace) {
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        return new ScoutRuntime(create(context, workspace, "primary", collector), collector);
    }

    /**
     * Creates an isolated Scout preview. Its visible result is deliberately not added to the
     * formal role context used by a later review run.
     */
    public PreviewHarness createPreview(
            ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace, String previewId) {
        requireSafeSegment(previewId, "previewId");
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        HarnessAgent agent = create(context, workspace, "preview-" + previewId, collector);
        return new PreviewHarness(agent, collector);
    }

    private HarnessAgent create(
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            String runId) {
        return create(context, workspace, runId, null);
    }

    private HarnessAgent create(
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            String runId,
            ScoutToolTraceCollector toolTraceCollector) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        RepositorySnapshot snapshot = repositoryToolFactory.requireSnapshot(context);
        ReviewRepositoryToolFactory.SharedProjectContext overview = repositoryToolFactory.sharedProjectContext(context);
        writeBaselineArtifact(context, workspace, overview, runId);
        java.nio.file.Path scoutWorkspace = workspaceLayout.scoutWorkspace(workspace, runId);
        String prompt = prompt(overview);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(SCOUT_LABEL)
                .agentId(SCOUT_LABEL)
                .description("Prepares a bounded public repository overview before review role activation")
                .defaultSessionId(context.runtimeId() + ":context-scout:" + runId)
                .workspace(scoutWorkspace)
                .filesystem(new LocalFilesystemSpec()
                        .project(snapshot.snapshotRepositoryRoot())
                        .projectWritable(false)
                        .mode(LocalFsMode.ROOTED)
                        .addRoot(snapshot.snapshotRepositoryRoot()))
                .model(new AgentScopeModelBridge(
                        modelGateway,
                        context,
                        RoleType.DIRECTOR,
                        "scout",
                        prompt,
                        INIT_RETRIEVAL_TOOLS,
                        INIT_RETRIEVAL_TOOLS,
                        toolTraceCollector == null ? null : toolTraceCollector::captureModelToolUse))
                .sysPrompt(prompt)
                .maxIters(properties.scoutMaxIterations())
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .skillsEnabled(false)
                .toolsConfig(readOnlyScoutTools())
                .permissionContext(workspacePermissions());
        if (toolTraceCollector != null) {
            builder.middleware(toolTraceCollector);
        }
        return builder.build();
    }

    /**
     * Filters the native AS2 tool registry after it is assembled. The underlying filesystem
     * remains the standard AS2 overlay, but Scout cannot receive or invoke write operations.
     */
    private static ToolsConfig readOnlyScoutTools() {
        ToolsConfig tools = new ToolsConfig();
        tools.setAllow(List.copyOf(INIT_RETRIEVAL_TOOLS));
        return tools;
    }

    private static PermissionContextState workspacePermissions() {
        return PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .build();
    }

    private void writeBaselineArtifact(
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            ReviewRepositoryToolFactory.SharedProjectContext overview,
            String runId) {
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "schemaVersion", 1,
                    "command", "context-scout-init",
                    "retrievalContract", java.util.Map.of(
                            "globFilesMaxCalls", 3,
                            "grepFilesMaxCalls", 6,
                            "readFileMaxCalls", 6,
                            "rootListing", "server-generated"),
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
                    "primary".equals(runId) ? "context-scout-baseline.json" : "context-scout-" + runId + "-baseline.json",
                    payload,
                    context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Context Scout baseline", exception);
        }
    }

    private static String prompt(ReviewRepositoryToolFactory.SharedProjectContext overview) {
        return "你是 Context Scout。你不是评审角色，不提交 Claim、不参与辩论、不决定 Gate。"
                + "你只能在受限工作区中工作：下层是不可修改的冻结代码快照，上层仅用于你的临时笔记；"
                + "不得调用 write_file 或 edit_file，也不得访问 Shell、宿主文件、角色会话或隐藏推理。"
                // [AIREVIEW-PLAN-089#2] 提示词约束：正文不得复述工具原文，发现一律用自己的话概述。
                + "不得在正文中引用或复述 [BEGIN_UNTRUSTED_TOOL_RESULT] 包裹的工具原文；工具发现一律用自己的话概述。"
                + "你执行的是受限的 context-scout-init 命令，而不是开放式项目探索。"
                + "服务器已经完成根目录、文件清单、模块根目录和需求摘要的初始化，并将其作为下方 INIT 清单提供；"
                + "不得调用 list_files，也不得重新枚举根目录。"
                + "只能使用 AS2 原生 glob_files、grep_files、read_file：glob_files 最多 3 次，grep_files 最多 6 次，"
                + "read_file 最多 6 次；每次必须服务于新的需求关联问题，禁止重复读取或全仓扫描。"
                + "每次调用前先评估剩余配额：优先用 read_file 验证高相关文件来形成结论，避免反复尝试投机性的检索模式；"
                + "剩余配额不足以继续验证时，立即以现有证据输出结论并将不足的信息标记为 unknown，不得用尽配额。"
                + "按“初始化清单 -> 需求关键词检索 -> 高相关文件验证 -> 立即输出”的固定顺序执行；"
                + "清单已经足以回答的信息不得再次检索。"
                + "识别与需求有关的模块、入口文件、构建方式与风险边界。"
                + "完成后仅用简体中文输出 JSON：summary、moduleRoots、entryPoints、constraints、risks、evidencePaths、roleScopes 七个字段；"
                + "每项结论必须列出 INIT 清单或已读取的快照相对路径；信息不足时明确标记 unknown，不可继续循环检索。"
                + "每完成一批工具调用后，用一行简体中文简述本批发现与下一步（不要长篇）。\n\n" // [AIREVIEW-PLAN-067#2]
                + "## INIT 清单（服务器生成，可信的结构性输入）\n"
                + overview.publicText(RoleType.PROJECT);
    }

    /** Persists the Scout's final visible response as a public, integrity-protected workspace artifact. */
    public ContextScoutConclusion recordResult(
            ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace, String visibleResult) {
        ContextScoutConclusion conclusion = conclusionService.capture(
                context.reviewId(), context.attemptNo(), visibleResult);
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
        return conclusion;
    }

    /** Writes a preview artifact without changing the shared role context cache. */
    public void recordPreviewResult(
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            String previewId,
            String visibleResult) {
        requireSafeSegment(previewId, "previewId");
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "schemaVersion", 1,
                    "agentId", SCOUT_LABEL,
                    "previewId", previewId,
                    "visibleResult", visibleResult == null ? "" : visibleResult));
            workspaceLayout.writeArtifact(
                    workspace,
                    ReviewWorkspaceLayout.ArtifactArea.PLANS,
                    "context-scout-preview-" + previewId + "-result.json",
                    payload,
                    context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Context Scout preview result", exception);
        }
    }

    private static void requireSafeSegment(String value, String name) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(name + " must be one safe path segment");
        }
    }

    /** Attempt-local Scout preview resources; closing it also discards transient tool output. */
    public record PreviewHarness(HarnessAgent agent, ScoutToolTraceCollector toolTraceCollector)
            implements AutoCloseable {

        public PreviewHarness {
            Objects.requireNonNull(agent, "agent must not be null");
            Objects.requireNonNull(toolTraceCollector, "toolTraceCollector must not be null");
        }

        @Override
        public void close() {
            try {
                agent.close();
            } finally {
                toolTraceCollector.clear();
            }
        }
    }

    /** Runtime resources for the non-voting Scout before the Director starts. */
    public record ScoutRuntime(HarnessAgent agent, ScoutToolTraceCollector toolTraceCollector) {
    }
}
