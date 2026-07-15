package ai.cc.chongming.review.infrastructure.agentscope;

/**
 * Ordered application-facing event that does not expose AgentScope event classes.
 *
 * @author wangli
 */
public record AgentRuntimeEvent(
        String runtimeId,
        long sequence,
        AgentRuntimeEventType type,
        String source,
        String payload) {
}
