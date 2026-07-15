package ai.cc.chongming.review.infrastructure.agentscope;

/**
 * Immutable runtime session identity returned to the review application.
 *
 * @author wangli
 */
public record AgentRuntimeSession(String runtimeId, String userId, String sessionId) {
}
