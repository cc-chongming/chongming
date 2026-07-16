package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;

import jakarta.validation.constraints.NotBlank;

/**
 * Identifies a review runtime session and its initial user input.
 *
 * @author wangli
 */
public record AgentRuntimeStartRequest(
        @NotBlank String runtimeId,
        @NotBlank String userId,
        @NotBlank String sessionId,
        @NotBlank String initialMessage,
        ReviewRuntimeContext runtimeContext) {

    public AgentRuntimeStartRequest(String runtimeId, String userId, String sessionId, String initialMessage) {
        this(runtimeId, userId, sessionId, initialMessage, null);
    }
}
