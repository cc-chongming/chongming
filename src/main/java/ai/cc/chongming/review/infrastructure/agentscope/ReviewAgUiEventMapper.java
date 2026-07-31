package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-017#3.2] Converts the existing AgentScope runtime stream to safe AG-UI events.
 *
 * @author wangli
 */
@Component
public class ReviewAgUiEventMapper {

    public static final String SCOUT_TOOL_CALL_EVENT_NAME = "chongming.tool-call.v1";

    private final RuntimeTraceRedactor redactor;

    public ReviewAgUiEventMapper(RuntimeTraceRedactor redactor) {
        this.redactor = redactor;
    }

    public List<AguiEvent> map(AgentEvent event, ReviewRuntimeContext context, RoleType role, String agentId) {
        return map(event, context, role, agentId, null, null);
    }

    /**
     * Maps a runtime event with an optional attempt-local native-tool observer. The observer is
     * only passed by Context Scout previews; formal role traces retain their existing contract.
     */
    public List<AguiEvent> map(
            AgentEvent event,
            ReviewRuntimeContext context,
            RoleType role,
            String agentId,
            String explicitRunId,
            ScoutToolTraceCollector toolTraceCollector) {
        String threadId = "review:" + context.reviewId().value();
        String runId = explicitRunId == null || explicitRunId.isBlank()
                ? context.runtimeId() + ":" + agentId
                : explicitRunId;
        if (event.getType() == AgentEventType.AGENT_START) {
            return List.of(
                    new AguiEvent.RunStarted(threadId, runId),
                    identityEvent(threadId, runId, role, agentId, event));
        }
        if (event instanceof AgentResultEvent result) {
            String text = result.getResult() == null ? "" : redactor.redactVisibleText(result.getResult().getTextContent());
            if (text == null || text.isBlank()) return List.of(new AguiEvent.RunFinished(threadId, runId));
            String messageId = result.getResult().getId();
            if (messageId == null || messageId.isBlank()) {
                messageId = runId + ":result:" + event.getId();
            }
            return List.of(
                    new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"),
                    new AguiEvent.TextMessageContent(threadId, runId, messageId, text),
                    new AguiEvent.TextMessageEnd(threadId, runId, messageId),
                    new AguiEvent.RunFinished(threadId, runId));
        }
        if (event instanceof ToolCallStartEvent tool) {
            AguiEvent.ToolCallStart standard =
                    new AguiEvent.ToolCallStart(threadId, runId, tool.getToolCallId(), tool.getToolCallName());
            return toolTrace(toolTraceCollector, tool.getToolCallId())
                    .map(trace -> List.<AguiEvent>of(
                            standard,
                            toolObservation(threadId, runId, context, tool.getToolCallId(), trace, "started", null)))
                    .orElseGet(() -> List.of(standard));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String summary = tool.getState() == null ? "工具执行结束" : "工具状态：" + tool.getState().name();
            List<AguiEvent> standard = List.of(
                    new AguiEvent.ToolCallEnd(threadId, runId, tool.getToolCallId()),
                    new AguiEvent.ToolCallResult(threadId, runId, tool.getToolCallId(), summary, "tool", tool.getReplyId()));
            return toolTrace(toolTraceCollector, tool.getToolCallId())
                    .map(trace -> {
                        List<AguiEvent> mapped = new java.util.ArrayList<>(standard);
                        mapped.add(toolObservation(
                                threadId,
                                runId,
                                context,
                                tool.getToolCallId(),
                                trace,
                                trace.state() == ToolResultState.SUCCESS ? "completed" : "failed",
                                tool.getState()));
                        return List.copyOf(mapped);
                    })
                    .orElse(standard);
        }
        return List.of(identityEvent(threadId, runId, role, agentId, event));
    }

    private Optional<ScoutToolTraceCollector.ToolTrace> toolTrace(
            ScoutToolTraceCollector collector, String toolCallId) {
        return collector == null ? Optional.empty() : collector.find(toolCallId);
    }

    private AguiEvent.Custom toolObservation(
            String threadId,
            String runId,
            ReviewRuntimeContext context,
            String toolCallId,
            ScoutToolTraceCollector.ToolTrace trace,
            String phase,
            ToolResultState terminalState) {
        RuntimeTraceRedactor.TracePayload input = redactor.redactToolInput(trace.input());
        boolean successful = terminalState == null || terminalState == ToolResultState.SUCCESS;
        RuntimeTraceRedactor.TracePayload output = successful
                ? redactor.redactToolOutput(trace.outputText())
                : safeFailureOutput(terminalState);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("phase", phase);
        value.put("reviewId", context.reviewId().value().toString());
        value.put("attemptNo", context.attemptNo());
        value.put("runtimeId", runId);
        value.put("toolCallId", toolCallId);
        value.put("toolName", trace.toolName());
        value.put("input", input.value());
        value.put("output", terminalState == null ? null : output.value());
        value.put("status", terminalState == null ? "RUNNING" : terminalState.name());
        value.put("elapsedMs", terminalState == null ? null : trace.elapsedMillis());
        value.put("truncated", input.truncated() || output.truncated());
        return new AguiEvent.Custom(threadId, runId, SCOUT_TOOL_CALL_EVENT_NAME, value);
    }

    private static RuntimeTraceRedactor.TracePayload safeFailureOutput(ToolResultState state) {
        String status = state == null ? "UNKNOWN" : state.name();
        return new RuntimeTraceRedactor.TracePayload(
                Map.of("errorCode", "TOOL_" + status, "summary", "工具执行未成功，详细异常已隐藏。"), false);
    }

    public AguiEvent.Custom lifecycle(
            ReviewRuntimeContext context, RoleType role, String agentId, String lifecycle) {
        String threadId = "review:" + context.reviewId().value();
        String runId = context.runtimeId() + ":" + agentId;
        return new AguiEvent.Custom(threadId, runId, "chongming.runtime-lifecycle.v1", Map.of(
                "role", displayRole(role, agentId), "agentId", agentId, "lifecycle", lifecycle));
    }

    private AguiEvent.Custom identityEvent(
            String threadId, String runId, RoleType role, String agentId, AgentEvent event) {
        String eventType = event.getType() == null ? "UNKNOWN" : event.getType().getValue();
        return new AguiEvent.Custom(threadId, runId, "chongming.runtime-event.v1", Map.of(
                "role", displayRole(role, agentId), "agentId", agentId, "eventType", eventType));
    }

    private static String displayRole(RoleType role, String agentId) {
        return "CONTEXT_SCOUT".equals(agentId) ? "CONTEXT_SCOUT" : role.name();
    }
}
