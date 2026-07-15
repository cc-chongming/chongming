package ai.cc.chongming.review.infrastructure.agentscope;

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
        @NotBlank String initialMessage) {
}
