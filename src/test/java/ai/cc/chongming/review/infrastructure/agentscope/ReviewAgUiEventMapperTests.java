package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * [AIREVIEW-PLAN-019#3.2] Contract coverage for browser-visible native Scout tool observations.
 *
 * @author wangli
 */
class ReviewAgUiEventMapperTests {

    @Test
    void mapsAnAgentResultWithoutAMessageAsRunCompletion() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());

        List<AguiEvent> events = mapper.map(
                new AgentResultEvent((Msg) null), context(), RoleType.DIRECTOR, "DIRECTOR");

        assertThat(events).singleElement().isInstanceOf(AguiEvent.RunFinished.class);
    }

    @Test
    void assignsAStableFallbackToolCallIdWhenTheRuntimeEventOmitsOne() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());

        List<AguiEvent> started = mapper.map(
                new ToolCallStartEvent("reply-1", null, null), context(), RoleType.DIRECTOR, "DIRECTOR");
        List<AguiEvent> completed = mapper.map(
                new ToolResultEndEvent("reply-1", null, null, ToolResultState.ERROR),
                context(),
                RoleType.DIRECTOR,
                "DIRECTOR");

        AguiEvent.ToolCallStart start = (AguiEvent.ToolCallStart) started.getFirst();
        AguiEvent.ToolCallEnd end = (AguiEvent.ToolCallEnd) completed.getFirst();
        AguiEvent.ToolCallResult result = (AguiEvent.ToolCallResult) completed.get(1);
        assertThat(start.toolCallId()).isEqualTo(end.toolCallId()).endsWith(":tool:reply-1:unknown_tool");
        assertThat(start.toolCallName()).isEqualTo("unknown_tool");
        assertThat(result.toolCallId()).isEqualTo(start.toolCallId());
        assertThat(result.content()).isEqualTo("工具状态：ERROR");
    }

    @Test
    void mapsStartedObservationBeforeTheNativeActingMiddlewareRuns() {
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        collector.captureModelToolUse(new ToolUseBlock("call-1", "glob_files", Map.of("pattern", "**/pom.xml")));
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());

        List<AguiEvent> events = mapper.map(
                new ToolCallStartEvent("reply-1", "call-1", "glob_files"),
                context(),
                RoleType.DIRECTOR,
                "CONTEXT_SCOUT_PREVIEW:1",
                "runtime-preview-1",
                collector);

        AguiEvent.Custom custom = events.stream()
                .filter(AguiEvent.Custom.class::isInstance)
                .map(AguiEvent.Custom.class::cast)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) custom.value();

        assertThat(events).hasSize(2);
        assertThat(payload)
                .containsEntry("phase", "started")
                .containsEntry("status", "RUNNING")
                .containsEntry("toolCallId", "call-1");
        assertThat(payload.get("input").toString()).contains("**/pom.xml");
        assertThat(payload.get("output")).isNull();
    }

    @Test
    void mapsOneNativeToolTraceToACompletedCustomObservationWithRawPayload() {
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        ToolUseBlock toolUse = new ToolUseBlock("call-1", "read_file", Map.of(
                "path", "E:\\aicode\\chongming\\src\\main\\App.java",
                "api_key", "debug-only-key"));
        collector.onActing(
                        null,
                        null,
                        new ActingInput(List.of(toolUse)),
                        ignored -> Flux.just(
                                new ToolResultTextDeltaEvent(
                                        "reply-1", "call-1", "read_file", "token=raw-tool-result\\nclass App {}"),
                                new ToolResultEndEvent("reply-1", "call-1", "read_file", ToolResultState.SUCCESS)))
                .collectList()
                .block();
        ReviewRuntimeContext context = context();
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());

        List<AguiEvent> events = mapper.map(
                new ToolResultEndEvent("reply-1", "call-1", "read_file", ToolResultState.SUCCESS),
                context,
                RoleType.DIRECTOR,
                "CONTEXT_SCOUT_PREVIEW:1",
                "runtime-preview-1",
                collector);

        AguiEvent.Custom custom = events.stream()
                .filter(AguiEvent.Custom.class::isInstance)
                .map(AguiEvent.Custom.class::cast)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) custom.value();

        assertThat(events).hasSize(3);
        assertThat(custom.name()).isEqualTo(ReviewAgUiEventMapper.SCOUT_TOOL_CALL_EVENT_NAME);
        assertThat(payload)
                .containsEntry("phase", "completed")
                .containsEntry("toolCallId", "call-1")
                .containsEntry("status", "SUCCESS")
                .containsEntry("runtimeId", "runtime-preview-1");
        assertThat(payload.get("input").toString())
                .contains("E:\\aicode\\chongming\\src\\main\\App.java")
                .contains("debug-only-key");
        assertThat(payload.get("output").toString())
                .contains("token=raw-tool-result")
                .contains("class App {}");
    }

    @Test
    void mapsFailedToolResultsWithTheRawObservedOutput() {
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        ToolUseBlock toolUse = new ToolUseBlock("call-2", "plan_write", Map.of("plan", "Use the reported risks"));
        collector.onActing(
                        null,
                        null,
                        new ActingInput(List.of(toolUse)),
                        ignored -> Flux.just(
                                new ToolResultTextDeltaEvent(
                                        "reply-2", "call-2", "plan_write", "plan storage unavailable"),
                                new ToolResultEndEvent(
                                        "reply-2", "call-2", "plan_write", ToolResultState.ERROR)))
                .collectList()
                .block();
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());

        List<AguiEvent> events = mapper.map(
                new ToolResultEndEvent("reply-2", "call-2", "plan_write", ToolResultState.ERROR),
                context(),
                RoleType.DIRECTOR,
                "DIRECTOR",
                "runtime-1",
                collector);

        AguiEvent.Custom custom = events.stream()
                .filter(AguiEvent.Custom.class::isInstance)
                .map(AguiEvent.Custom.class::cast)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) custom.value();

        assertThat(payload)
                .containsEntry("phase", "failed")
                .containsEntry("status", "ERROR");
        assertThat(payload.get("output")).isEqualTo("plan storage unavailable");
    }

    private static ReviewRuntimeContext context() {
        return new ReviewRuntimeContext(
                new ReviewId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                1,
                "tester",
                "trace-1",
                IntakeCancellation.neverCancelled());
    }
}
