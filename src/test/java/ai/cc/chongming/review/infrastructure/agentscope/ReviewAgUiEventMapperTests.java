package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
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
 * <p>
 * [AIREVIEW-PLAN-023#8]
 *
 * @author zyj
 */
class ReviewAgUiEventMapperTests {

    @Test
    void mapsTextBlockLifecycleToOneStableAgUiMessage() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());
        ReviewRuntimeContext context = context();

        List<AguiEvent> started = mapper.map(
                new TextBlockStartEvent("reply-1", "text"), context, RoleType.PRODUCT, "PRODUCT");
        List<AguiEvent> content = mapper.map(
                new TextBlockDeltaEvent("reply-1", "text", "公开结论"), context, RoleType.PRODUCT, "PRODUCT");
        List<AguiEvent> ended = mapper.map(
                new TextBlockEndEvent("reply-1", "text"), context, RoleType.PRODUCT, "PRODUCT");

        AguiEvent.TextMessageStart start = (AguiEvent.TextMessageStart) started.getFirst();
        AguiEvent.TextMessageContent delta = (AguiEvent.TextMessageContent) content.getFirst();
        AguiEvent.TextMessageEnd end = (AguiEvent.TextMessageEnd) ended.getFirst();
        assertThat(start.messageId()).isEqualTo(delta.messageId()).isEqualTo(end.messageId());
        assertThat(start.messageId()).endsWith(":message:reply-1:text");
        assertThat(delta.delta()).isEqualTo("公开结论");
    }

    @Test
    void doesNotDuplicateFinalResultAfterStreamingText() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());
        ReviewRuntimeContext context = context();
        mapper.map(new TextBlockStartEvent("reply-1", "text"), context, RoleType.PRODUCT, "PRODUCT");
        mapper.map(new TextBlockDeltaEvent("reply-1", "text", "公开结论"), context, RoleType.PRODUCT, "PRODUCT");
        mapper.map(new TextBlockEndEvent("reply-1", "text"), context, RoleType.PRODUCT, "PRODUCT");

        List<AguiEvent> events = mapper.map(
                new AgentResultEvent(io.agentscope.core.message.AssistantMessage.builder()
                        .id("result-1")
                        .name("agent")
                        .content(io.agentscope.core.message.TextBlock.builder().text("公开结论").build())
                        .build()),
                context,
                RoleType.PRODUCT,
                "PRODUCT");

        assertThat(events).singleElement().isInstanceOf(AguiEvent.RunFinished.class);
    }

    @Test
    void blocksThinkingDeltasInsteadOfPublishingReasoningOrCustomEvents() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());

        List<AguiEvent> events = mapper.map(
                new ThinkingBlockDeltaEvent("reply-1", "thinking", "内部推理"),
                context(),
                RoleType.PRODUCT,
                "PRODUCT");

        assertThat(events).isEmpty();
    }

    @Test
    void dropsNonPublicRuntimeEventsInsteadOfTurningThemIntoCustomNoise() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());

        List<AguiEvent> events = mapper.map(
                new ModelCallStartEvent("reply-1"), context(), RoleType.PRODUCT, "PRODUCT");

        assertThat(events).isEmpty();
    }

    @Test
    void failedLifecycleClosesOpenStreamingMessageBeforePublishingRunError() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());
        ReviewRuntimeContext context = context();
        mapper.map(new TextBlockStartEvent("reply-1", "text"), context, RoleType.PRODUCT, "PRODUCT");
        mapper.map(new TextBlockDeltaEvent("reply-1", "text", "部分输出"), context, RoleType.PRODUCT, "PRODUCT");

        List<AguiEvent> terminal = mapper.lifecycle(context, RoleType.PRODUCT, "PRODUCT", "FAILED");
        List<AguiEvent> afterFailure = mapper.map(
                new TextBlockEndEvent("reply-1", "text"), context, RoleType.PRODUCT, "PRODUCT");

        assertThat(terminal)
                .hasSize(3)
                .element(0).isInstanceOf(AguiEvent.TextMessageEnd.class);
        assertThat(terminal.get(1)).isInstanceOf(AguiEvent.Custom.class);
        AguiEvent.RunError runError = (AguiEvent.RunError) terminal.get(2);
        assertThat(runError.code()).isEqualTo("FAILED");
        assertThat(runError.message()).contains("PRODUCT", "FAILED");
        assertThat(afterFailure).isEmpty();
    }

    @Test
    void cancelledLifecycleAlsoClosesItsRunWithAnError() {
        for (String lifecycle : List.of("CANCELLED")) {
            ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());
            ReviewRuntimeContext context = context();
            mapper.map(new TextBlockStartEvent("reply-1", "text"), context, RoleType.DIRECTOR, "DIRECTOR");

            List<AguiEvent> terminal = mapper.lifecycle(
                    context, RoleType.DIRECTOR, "DIRECTOR", lifecycle);

            assertThat(terminal.getFirst()).isInstanceOf(AguiEvent.TextMessageEnd.class);
            assertThat(terminal.getLast()).isInstanceOf(AguiEvent.RunError.class);
            assertThat(((AguiEvent.RunError) terminal.getLast()).code()).isEqualTo(lifecycle);
        }
    }

    @Test
    void closedLifecycleEndsOpenTextWithoutReportingAnError() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());
        ReviewRuntimeContext context = context();
        mapper.map(new TextBlockStartEvent("reply-1", "text"), context, RoleType.DIRECTOR, "DIRECTOR");

        List<AguiEvent> terminal = mapper.lifecycle(context, RoleType.DIRECTOR, "DIRECTOR", "CLOSED");

        assertThat(terminal.getFirst()).isInstanceOf(AguiEvent.TextMessageEnd.class);
        assertThat(terminal.getLast()).isInstanceOf(AguiEvent.Custom.class);
        assertThat(terminal).noneMatch(AguiEvent.RunError.class::isInstance);
        assertThat(terminal).noneMatch(AguiEvent.RunFinished.class::isInstance);
    }

    @Test
    void degradedLifecycleEndsOpenTextButRemainsAdvisory() {
        ReviewAgUiEventMapper mapper = new ReviewAgUiEventMapper(new RuntimeTraceRedactor());
        ReviewRuntimeContext context = context();
        mapper.map(new TextBlockStartEvent("reply-1", "text"), context, RoleType.DIRECTOR, "CONTEXT_SCOUT");

        List<AguiEvent> terminal = mapper.lifecycle(
                context, RoleType.DIRECTOR, "CONTEXT_SCOUT", "DEGRADED");

        assertThat(terminal.getFirst()).isInstanceOf(AguiEvent.TextMessageEnd.class);
        assertThat(terminal.getLast()).isInstanceOf(AguiEvent.Custom.class);
        assertThat(terminal).noneMatch(AguiEvent.RunError.class::isInstance);
    }

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
        assertThat(payload.get("output"))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("text", "plan storage unavailable");
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
