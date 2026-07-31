package ai.cc.chongming.review.infrastructure.agentscope;

/**
 * Application-facing lifecycle events emitted by an agent runtime adapter.
 *
 * @author wangli
 */
public enum AgentRuntimeEventType {
    STARTED,
    ROLE_REGISTERED,
    MESSAGE_SENT,
    CANCELLED,
    RESUMED,
    RAW_EVENT,
    DEGRADED,
    FAILED
}
