package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Converts raw AgentScope observations into redacted runtime telemetry, never into business facts.
 *
 * @author wangli
 */
@Component
public class AgentEventAdapter {

    private static final Set<AgentEventType> OBSERVABLE_EVENT_TYPES = EnumSet.of(
            AgentEventType.AGENT_START,
            AgentEventType.AGENT_END,
            AgentEventType.AGENT_RESULT,
            AgentEventType.MODEL_CALL_START,
            AgentEventType.MODEL_CALL_END,
            AgentEventType.TOOL_CALL_START,
            AgentEventType.TOOL_CALL_END,
            AgentEventType.TOOL_RESULT_END,
            AgentEventType.EXCEED_MAX_ITERS,
            AgentEventType.REQUEST_STOP,
            AgentEventType.ALL_TOOLS_DENIED);

    private final AtomicLong ignoredEventCount = new AtomicLong();

    /**
     * Safely adapts a raw event; unknown event categories are ignored and counted.
     */
    public Optional<RuntimeObservation> adapt(
            AgentEvent event,
            ReviewRuntimeContext runtimeContext,
            RoleType actorRole,
            String agentId,
            String sessionId,
            ReviewStage stage) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        requireText(agentId, "agentId");
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(stage, "stage must not be null");
        AgentEventType eventType = event.getType();
        if (eventType == null || !OBSERVABLE_EVENT_TYPES.contains(eventType)) {
            ignoredEventCount.incrementAndGet();
            return Optional.empty();
        }
        String source = event.getSource();
        return Optional.of(new RuntimeObservation(
                runtimeContext.runtimeId(),
                runtimeContext.reviewId().value().toString(),
                runtimeContext.attemptNo(),
                actorRole,
                agentId,
                sessionId,
                stage,
                eventType.getValue(),
                safeMetadata(event, "toolName"),
                safeMetadata(event, "parentId"),
                source == null || source.isBlank() ? null : source,
                Instant.now()));
    }

    /**
     * Returns the number of event categories ignored for forward compatibility diagnostics.
     */
    public long ignoredEventCount() {
        return ignoredEventCount.get();
    }

    private String safeMetadata(AgentEvent event, String key) {
        Map<String, Object> metadata = event.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * Redacted AgentScope telemetry. Strongly typed business events remain the responsibility of tools.
     *
     * @author wangli
     */
    public record RuntimeObservation(
            String runtimeId,
            String reviewId,
            int attemptNo,
            RoleType actorRole,
            String agentId,
            String sessionId,
            ReviewStage stage,
            String rawEventType,
            String toolName,
            String parentId,
            String source,
            Instant occurredAt) {
    }
}
