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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * Verifies timeout-zero background sub-agent execution in AgentScope 2.0.0.
 *
 * @author wangli
 */
class HarnessBackgroundSubagentCompatibilityTests {

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
    void timeoutZeroRunsSubagentInBackgroundWithoutBlockingParent() throws InterruptedException {
        Model parentModel = mock(Model.class);
        when(parentModel.getModelName()).thenReturn("parent-scripted-model");
        when(parentModel.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(toolCall("parent-1", "agent_spawn", Map.of(
                        "agent_id", "requirements-reviewer",
                        "task", "review asynchronously",
                        "timeout_seconds", 0))))
                .thenReturn(Flux.just(stop("parent-2", "background task accepted")));

        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch childFinished = new CountDownLatch(1);
        Model childModel = mock(Model.class);
        when(childModel.getModelName()).thenReturn("child-scripted-model");
        when(childModel.stream(anyList(), any(), any())).thenAnswer(ignored -> Flux.defer(() -> {
            childStarted.countDown();
            try {
                if (!releaseChild.await(10, TimeUnit.SECONDS)) {
                    return Flux.error(new IllegalStateException("test did not release background child"));
                }
                return Flux.just(stop("child-1", "background review complete"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Flux.error(exception);
            } finally {
                childFinished.countDown();
            }
        }));

        AtomicReference<HarnessAgent> spawnedChild = new AtomicReference<>();
        HarnessAgent director = HarnessAgent.builder()
                .name("review-director")
                .model(parentModel)
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .disableMemoryHooks()
                .subagentFactory("requirements-reviewer", ignored -> {
                    HarnessAgent child = HarnessAgent.builder()
                            .name("requirements-reviewer")
                            .model(childModel)
                            .workspace(workspace.resolve("requirements-reviewer"))
                            .abstractFilesystem(new LocalFilesystem(workspace.resolve("requirements-reviewer")))
                            .disableMemoryHooks()
                            .build();
                    spawnedChild.set(child);
                    return child;
                })
                .build();

        try {
            List<AgentEvent> events = director.streamEvents(
                            List.of(Msg.builder().role(MsgRole.USER).textContent("start review").build()),
                            RuntimeContext.builder().userId("user-001").sessionId("review-001").build())
                    .collectList()
                    .block(Duration.ofSeconds(3));

            assertThat(events).isNotEmpty();
            assertThat(childStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(spawnedChild.get()).isNotNull();
        } finally {
            releaseChild.countDown();
            assertThat(childFinished.await(3, TimeUnit.SECONDS)).isTrue();
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
