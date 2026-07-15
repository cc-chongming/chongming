package ai.cc.chongming.review.infrastructure.agentscope;

/**
 * Application-facing lifecycle events emitted by an agent runtime adapter.
 *
 * @author wangli
 */
public enum AgentRuntimeEventType {
    STARTED,
    MESSAGE_SENT,
    CANCELLED,
    RESUMED
}
