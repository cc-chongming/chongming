package ai.cc.chongming.review.compatibility;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins AgentScope 2.0.0 behavior that spawned Harness sub-agents do not inherit Plan Mode.
 *
 * @author wangli
 */
class HarnessPlanModeSubagentCompatibilityTests {

    @TempDir
    Path workspace;

    @TempDir
    Path stateHome;

    private String previousStateHome;

    @BeforeEach
    void isolateStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreStateHome() {
        if (previousStateHome == null) {
            System.clearProperty("agentscope.state.home");
        } else {
            System.setProperty("agentscope.state.home", previousStateHome);
        }
    }

    @Test
    void spawnedHarnessDoesNotInheritParentPlanMode() {
        Model parentModel = mock(Model.class);
        when(parentModel.getModelName()).thenReturn("parent-scripted-model");
        when(parentModel.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(toolCall("parent-1", "agent_spawn", Map.of(
                        "agent_id", "security-reviewer",
                        "task", "review the plan boundary",
                        "timeout_seconds", 30))))
                .thenReturn(Flux.just(stop("parent-2", "delegation complete")));

        Model childModel = mock(Model.class);
        when(childModel.getModelName()).thenReturn("child-scripted-model");
        when(childModel.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(stop("child-1", "plan boundary reviewed")));

        AtomicReference<HarnessAgent> spawnedChild = new AtomicReference<>();
        HarnessAgent director = HarnessAgent.builder()
                .name("review-director")
                .model(parentModel)
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .enablePlanMode()
                .disableMemoryHooks()
                .subagentFactory("security-reviewer", ignored -> {
                    HarnessAgent child = HarnessAgent.builder()
                            .name("security-reviewer")
                            .model(childModel)
                            .workspace(workspace.resolve("security-reviewer"))
                            .abstractFilesystem(new LocalFilesystem(workspace.resolve("security-reviewer")))
                            .disableMemoryHooks()
                            .build();
                    spawnedChild.set(child);
                    return child;
                })
                .build();

        try {
            RuntimeContext context = RuntimeContext.builder()
                    .userId("user-001")
                    .sessionId("review-001")
                    .build();
            director.enterPlanMode(context.getUserId(), context.getSessionId());

            List<AgentEvent> events = director.streamEvents(
                            List.of(Msg.builder().role(MsgRole.USER).textContent("start review").build()),
                            context)
                    .collectList()
                    .block();

            HarnessAgent child = spawnedChild.get();
            assertThat(child).isNotNull();
            assertThat(events).anyMatch(event -> event.getSource() != null);
            assertThat(child.getAgentState().getPlanModeContext().isPlanActive()).isFalse();
        } finally {
            director.close();
            HarnessAgent child = spawnedChild.get();
            if (child != null) {
                child.close();
            }
        }
    }

    private static ChatResponse stop(String id, String text) {
        return new ChatResponse(id, List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    private static ChatResponse toolCall(String id, String toolName, Map<String, Object> input) {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("tool-" + id)
                .name(toolName)
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
        return new ChatResponse(id, List.of(toolUse), null, Map.of(), "tool_use");
    }
}