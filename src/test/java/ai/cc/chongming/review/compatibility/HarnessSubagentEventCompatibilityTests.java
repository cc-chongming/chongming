package ai.cc.chongming.review.compatibility;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * Pins public sub-agent event forwarding and persistence declaration behavior in AgentScope 2.0.0.
 *
 * @author wangli
 */
class HarnessSubagentEventCompatibilityTests {

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
    void streamEventsForwardsSynchronousChildEventsWithSource() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("scripted-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(toolCall("parent-1", "agent_spawn", Map.of(
                        "agent_id", "requirements-reviewer",
                        "task", "review acceptance criteria",
                        "label", "requirements",
                        "timeout_seconds", 30))))
                .thenReturn(Flux.just(stop("child-1", "criteria reviewed")))
                .thenReturn(Flux.just(stop("parent-2", "review completed")));

        SubagentDeclaration reviewer = SubagentDeclaration.builder()
                .name("requirements-reviewer")
                .description("reviews requirements")
                .inlineAgentsBody("Review the supplied acceptance criteria.")
                .persistSession(true)
                .build();

        try (HarnessAgent director = HarnessAgent.builder()
                .name("review-director")
                .model(model)
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .disableMemoryHooks()
                .subagent(reviewer)
                .build()) {
            List<AgentEvent> events = director.streamEvents(
                            List.of(Msg.builder().role(MsgRole.USER).textContent("start review").build()),
                            RuntimeContext.builder().userId("user-001").sessionId("review-001").build())
                    .collectList()
                    .block();

            assertThat(events).isNotEmpty();
            assertThat(events).anyMatch(event -> event.getSource() == null);
            assertThat(events)
                    .filteredOn(event -> event.getSource() != null)
                    .isNotEmpty()
                    .allMatch(event -> event.getSource().contains("requirements-reviewer"));
        }
    }

    @Test
    void agentSendUsesStableLabelToReusePersistentChildSession() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("scripted-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(toolCall("parent-1", "agent_spawn", Map.of(
                        "agent_id", "requirements-reviewer",
                        "task", "review the first draft",
                        "label", "requirements",
                        "timeout_seconds", 30))))
                .thenReturn(Flux.just(stop("child-1", "first review")))
                .thenReturn(Flux.just(toolCall("parent-2", "agent_send", Map.of(
                        "label", "requirements",
                        "message", "challenge the missing acceptance criteria",
                        "timeout_seconds", 30))))
                .thenReturn(Flux.just(stop("child-2", "follow-up review")))
                .thenReturn(Flux.just(stop("parent-3", "debate completed")));

        SubagentDeclaration reviewer = SubagentDeclaration.builder()
                .name("requirements-reviewer")
                .description("reviews requirements")
                .inlineAgentsBody("Review the supplied acceptance criteria.")
                .persistSession(true)
                .build();

        try (HarnessAgent director = HarnessAgent.builder()
                .name("review-director")
                .model(model)
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .disableMemoryHooks()
                .subagent(reviewer)
                .build()) {
            List<AgentEvent> events = director.streamEvents(
                            List.of(Msg.builder().role(MsgRole.USER).textContent("start review").build()),
                            RuntimeContext.builder().userId("user-001").sessionId("review-001").build())
                    .collectList()
                    .block();

            assertThat(events)
                    .filteredOn(event -> event.getType() == AgentEventType.AGENT_START)
                    .filteredOn(event -> event.getSource() != null)
                    .hasSize(2)
                    .allMatch(event -> event.getSource().contains("requirements-reviewer"));
        }
    }

    @Test
    void persistentSubagentDeclarationsInheritPermissionsByDefault() {
        SubagentDeclaration declaration = SubagentDeclaration.builder()
                .name("backend-reviewer")
                .description("reviews service contracts")
                .inlineAgentsBody("Review backend contracts.")
                .persistSession(true)
                .build();

        assertThat(declaration.isPersistSession()).isTrue();
        assertThat(declaration.isInheritParentPermissions()).isTrue();
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
