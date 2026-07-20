package ai.cc.chongming.review.domain.gateway;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Vendor-neutral boundary for public, auditable model generation requests.
 *
 * @author wangli
 */
public interface ModelGateway {

    /**
     * Generates one public response without persisting model-private reasoning.
     *
     * @param request server-assembled prompt and runtime metadata
     * @param cancellation cooperative cancellation signal
     * @return public response and measurable call metadata
     */
    Mono<ModelResponse> generate(ModelRequest request, IntakeCancellation cancellation);

    /**
     * Server-controlled model request containing only the role's permitted public context.
     *
     * @author wangli
     */
    record ModelRequest(
            ReviewId reviewId,
            RoleType roleType,
            String profileId,
            String promptVersion,
            String systemInstruction,
            String publicContext,
            Set<String> allowedTools,
            List<ToolDefinition> tools,
            String traceId) {

        public ModelRequest {
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            Objects.requireNonNull(roleType, "roleType must not be null");
            requireText(profileId, "profileId");
            requireText(promptVersion, "promptVersion");
            requireText(systemInstruction, "systemInstruction");
            Objects.requireNonNull(publicContext, "publicContext must not be null");
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
            tools = tools == null ? List.of() : List.copyOf(tools);
            requireText(traceId, "traceId");
        }

        public ModelRequest(ReviewId reviewId, RoleType roleType, String profileId, String promptVersion,
                String systemInstruction, String publicContext, Set<String> allowedTools, String traceId) {
            this(reviewId, roleType, profileId, promptVersion, systemInstruction, publicContext, allowedTools, List.of(), traceId);
        }
    }

    /**
     * Public model response normalized across commercial providers.
     *
     * @author wangli
     */
    record ModelResponse(
            String responseId,
            String modelName,
            String publicText,
            Usage usage,
            FinishReason finishReason,
            Duration latency,
            int attempts,
            List<ToolCall> toolCalls,
            String traceId) {

        public ModelResponse {
            requireText(responseId, "responseId");
            requireText(modelName, "modelName");
            publicText = publicText == null ? "" : publicText;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if ((publicText == null || publicText.isBlank()) && toolCalls.isEmpty()) {
                throw new IllegalArgumentException("publicText or toolCalls must not be empty");
            }
            Objects.requireNonNull(usage, "usage must not be null");
            Objects.requireNonNull(finishReason, "finishReason must not be null");
            if (latency == null || latency.isNegative()) {
                throw new IllegalArgumentException("latency must not be negative");
            }
            if (attempts < 1 || attempts > 3) {
                throw new IllegalArgumentException("attempts must be between 1 and 3");
            }
            requireText(traceId, "traceId");
        }

        public ModelResponse(String responseId, String modelName, String publicText, Usage usage,
                FinishReason finishReason, Duration latency, int attempts, String traceId) {
            this(responseId, modelName, publicText, usage, finishReason, latency, attempts, List.of(), traceId);
        }
    }

    /**
     * Token accounting provided by the provider response when available.
     *
     * @author wangli
     */
    record Usage(long inputTokens, long outputTokens, long totalTokens) {

        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("Token usage must not be negative");
            }
        }
    }

    /**
     * Provider-independent terminal reason exposed to workflow code.
     *
     * @author wangli
     */
    enum FinishReason {
        STOP,
        LENGTH,
        TOOL_CALL,
        CONTENT_FILTER,
        UNKNOWN
    }

    record ToolDefinition(String name, String description, Map<String, Object> parameters, Boolean strict) {
        public ToolDefinition {
            requireText(name, "name");
            requireText(description, "description");
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    record ToolCall(String id, String name, Map<String, Object> input) {
        public ToolCall {
            requireText(id, "id");
            requireText(name, "name");
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
