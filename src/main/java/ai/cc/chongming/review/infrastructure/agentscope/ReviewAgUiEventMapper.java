package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-017#3.2] Converts the existing AgentScope runtime stream to safe AG-UI events.
 *
 * @author wangli
 */
@Component
public class ReviewAgUiEventMapper {

    private final RuntimeTraceRedactor redactor;

    public ReviewAgUiEventMapper(RuntimeTraceRedactor redactor) {
        this.redactor = redactor;
    }

    public List<AguiEvent> map(AgentEvent event, ReviewRuntimeContext context, RoleType role, String agentId) {
        String threadId = "review:" + context.reviewId().value();
        String runId = context.runtimeId() + ":" + agentId;
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
            return List.of(new AguiEvent.ToolCallStart(threadId, runId, tool.getToolCallId(), tool.getToolCallName()));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String summary = tool.getState() == null ? "工具执行结束" : "工具状态：" + tool.getState().name();
            return List.of(
                    new AguiEvent.ToolCallEnd(threadId, runId, tool.getToolCallId()),
                    new AguiEvent.ToolCallResult(threadId, runId, tool.getToolCallId(), summary, "tool", tool.getReplyId()));
        }
        return List.of(identityEvent(threadId, runId, role, agentId, event));
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
