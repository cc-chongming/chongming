package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-023#8] Converts the AgentScope runtime stream to safe, public AG-UI events.
 *
 * @author zyj
 */
@Component
public class ReviewAgUiEventMapper {

    public static final String SCOUT_TOOL_CALL_EVENT_NAME = "chongming.tool-call.v1";

    private final RuntimeTraceRedactor redactor;
    private final Map<String, String> openTextMessages = new ConcurrentHashMap<>();
    private final Set<String> streamedRuns = ConcurrentHashMap.newKeySet();

    public ReviewAgUiEventMapper(RuntimeTraceRedactor redactor) {
        this.redactor = redactor;
    }

    public List<AguiEvent> map(AgentEvent event, ReviewRuntimeContext context, RoleType role, String agentId) {
        return map(event, context, role, agentId, null, null);
    }

    /**
     * Maps a runtime event with an optional attempt-local observer owned by its Harness runtime.
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
        if (event instanceof TextBlockStartEvent text) {
            String key = textKey(runId, text.getReplyId(), text.getBlockId());
            String messageId = textMessageId(runId, text.getReplyId(), text.getBlockId());
            if (openTextMessages.putIfAbsent(key, messageId) != null) {
                return List.of();
            }
            streamedRuns.add(runId);
            return List.of(new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"));
        }
        if (event instanceof TextBlockDeltaEvent text) {
            String delta = redactor.redactVisibleText(text.getDelta());
            if (delta == null || delta.isEmpty()) {
                return List.of();
            }
            String key = textKey(runId, text.getReplyId(), text.getBlockId());
            String messageId = textMessageId(runId, text.getReplyId(), text.getBlockId());
            boolean missingStart = openTextMessages.putIfAbsent(key, messageId) == null;
            streamedRuns.add(runId);
            if (missingStart) {
                return List.of(
                        new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"),
                        new AguiEvent.TextMessageContent(threadId, runId, messageId, delta));
            }
            return List.of(new AguiEvent.TextMessageContent(threadId, runId, messageId, delta));
        }
        if (event instanceof TextBlockEndEvent text) {
            String key = textKey(runId, text.getReplyId(), text.getBlockId());
            String messageId = openTextMessages.remove(key);
            return messageId == null
                    ? List.of()
                    : List.of(new AguiEvent.TextMessageEnd(threadId, runId, messageId));
        }
        if (event instanceof ThinkingBlockStartEvent
                || event instanceof ThinkingBlockDeltaEvent
                || event instanceof ThinkingBlockEndEvent) {
            return List.of();
        }
        if (event instanceof AgentResultEvent result) {
            String text = result.getResult() == null ? "" : redactor.redactVisibleText(result.getResult().getTextContent());
            String messageId = result.getResult() == null ? null : result.getResult().getId();
            if (messageId == null || messageId.isBlank()) {
                messageId = runId + ":result:" + event.getId();
            }
            List<AguiEvent> mapped = new java.util.ArrayList<>();
            boolean streamed = streamedRuns.remove(runId);
            mapped.addAll(drainOpenTextMessages(threadId, runId));
            if (!streamed && text != null && !text.isBlank()) {
                mapped.add(new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"));
                mapped.add(new AguiEvent.TextMessageContent(threadId, runId, messageId, text));
                mapped.add(new AguiEvent.TextMessageEnd(threadId, runId, messageId));
            }
            mapped.add(new AguiEvent.RunFinished(threadId, runId));
            return List.copyOf(mapped);
        }
        if (event.getType() == AgentEventType.AGENT_END) {
            streamedRuns.remove(runId);
            return drainOpenTextMessages(threadId, runId);
        }
        if (event instanceof ToolCallStartEvent tool) {
            String toolCallId = toolCallId(runId, tool.getToolCallId(), tool.getReplyId(), tool.getToolCallName(), event);
            String toolCallName = toolCallName(tool.getToolCallName());
            AguiEvent.ToolCallStart standard =
                    new AguiEvent.ToolCallStart(threadId, runId, toolCallId, toolCallName);
            return toolTrace(toolTraceCollector, toolCallId)
                    .map(trace -> List.<AguiEvent>of(
                            standard,
                            toolObservation(threadId, runId, context, toolCallId, trace, "started", null)))
                    .orElseGet(() -> List.of(standard));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String toolCallId = toolCallId(runId, tool.getToolCallId(), tool.getReplyId(), tool.getToolCallName(), event);
            String summary = tool.getState() == null ? "工具执行结束" : "工具状态：" + tool.getState().name();
            List<AguiEvent> standard = List.of(
                    new AguiEvent.ToolCallEnd(threadId, runId, toolCallId),
                    new AguiEvent.ToolCallResult(threadId, runId, toolCallId, summary, "tool", tool.getReplyId()));
            return toolTrace(toolTraceCollector, toolCallId)
                    .map(trace -> {
                        List<AguiEvent> mapped = new java.util.ArrayList<>(standard);
                        mapped.add(toolObservation(
                                threadId,
                                runId,
                                context,
                                toolCallId,
                                trace,
                                trace.state() == ToolResultState.SUCCESS ? "completed" : "failed",
                                tool.getState()));
                        return List.copyOf(mapped);
                    })
                    .orElse(standard);
        }
        return List.of();
    }

    private static String textKey(String runId, String replyId, String blockId) {
        return runId + "\u0000" + safePart(replyId, "reply") + "\u0000" + safePart(blockId, "text");
    }

    private static String textMessageId(String runId, String replyId, String blockId) {
        return runId + ":message:" + safePart(replyId, "reply") + ":" + safePart(blockId, "text");
    }

    private static String safePart(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<AguiEvent> drainOpenTextMessages(String threadId, String runId) {
        String prefix = runId + "\u0000";
        List<AguiEvent> ended = new java.util.ArrayList<>();
        openTextMessages.forEach((key, messageId) -> {
            if (key.startsWith(prefix) && openTextMessages.remove(key, messageId)) {
                ended.add(new AguiEvent.TextMessageEnd(threadId, runId, messageId));
            }
        });
        return List.copyOf(ended);
    }

    private Optional<ScoutToolTraceCollector.ToolTrace> toolTrace(
            ScoutToolTraceCollector collector, String toolCallId) {
        return collector == null || toolCallId == null || toolCallId.isBlank()
                ? Optional.empty()
                : collector.find(toolCallId);
    }

    private static String toolCallId(
            String runId, String toolCallId, String replyId, String toolCallName, AgentEvent event) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            return toolCallId;
        }
        String replyPart = replyId == null || replyId.isBlank()
                ? (event.getId() == null || event.getId().isBlank() ? "unknown" : event.getId())
                : replyId;
        return runId + ":tool:" + replyPart + ":" + toolCallName(toolCallName);
    }

    private static String toolCallName(String toolCallName) {
        return toolCallName == null || toolCallName.isBlank() ? "unknown_tool" : toolCallName;
    }

    private AguiEvent.Custom toolObservation(
            String threadId,
            String runId,
            ReviewRuntimeContext context,
            String toolCallId,
            ScoutToolTraceCollector.ToolTrace trace,
            String phase,
            ToolResultState terminalState) {
        RuntimeTraceRedactor.TracePayload input = redactor.rawToolInput(trace.input());
        RuntimeTraceRedactor.TracePayload output = redactor.rawToolOutput(trace.outputText());
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

    public List<AguiEvent> lifecycle(
            ReviewRuntimeContext context, RoleType role, String agentId, String lifecycle) {
        String threadId = "review:" + context.reviewId().value();
        String runId = context.runtimeId() + ":" + agentId;
        AguiEvent.Custom lifecycleEvent = new AguiEvent.Custom(
                threadId,
                runId,
                "chongming.runtime-lifecycle.v1",
                Map.of("role", displayRole(role, agentId), "agentId", agentId, "lifecycle", lifecycle));
        if (!Set.of("FAILED", "CANCELLED", "CLOSED", "DEGRADED").contains(lifecycle)) {
            return List.of(lifecycleEvent);
        }

        streamedRuns.remove(runId);
        List<AguiEvent> terminal = new java.util.ArrayList<>(drainOpenTextMessages(threadId, runId));
        terminal.add(lifecycleEvent);
        if (Set.of("FAILED", "CANCELLED").contains(lifecycle)) {
            terminal.add(new AguiEvent.RunError(
                    threadId,
                    runId,
                    displayRole(role, agentId) + " runtime ended with lifecycle " + lifecycle,
                    lifecycle));
        }
        return List.copyOf(terminal);
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
