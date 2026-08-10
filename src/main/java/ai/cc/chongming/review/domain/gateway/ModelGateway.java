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
import reactor.core.publisher.Flux;

/**
 * Vendor-neutral boundary for public, auditable model generation requests.
 * <p>
 * [AIREVIEW-PLAN-023#8]
 *
 * @author zyj
 */
public interface ModelGateway {

    /**
     * Generates one complete public response. Provider reasoning is outside the public runtime
     * boundary and must not be published or persisted.
     *
     * @param request      server-assembled prompt and runtime metadata
     * @param cancellation cooperative cancellation signal
     * @return public response and measurable call metadata
     */
    Mono<ModelResponse> generate(ModelRequest request, IntakeCancellation cancellation);

    /**
     * Streams public model output. Gateways without a streaming provider retain an explicit
     * compatibility path that emits the synchronous result as one terminal chunk.
     */
    default Flux<ModelStreamChunk> stream(ModelRequest request, IntakeCancellation cancellation) {
        return generate(request, cancellation).map(ModelStreamChunk::from).flux();
    }

    /**
     * Server-controlled model request containing only the role's permitted public context.
     *
     * @author zyj
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
     * @author zyj
     */
    record ModelResponse(
            String responseId,
            String modelName,
            String publicText,
            String thinkingText,
            Usage usage,
            FinishReason finishReason,
            Duration latency,
            int attempts,
            List<ToolCall> toolCalls,
            String traceId) {

        private static final int MAX_ATTEMPTS = 6;

        public ModelResponse {
            requireText(responseId, "responseId");
            requireText(modelName, "modelName");
            publicText = publicText == null ? "" : publicText;
            thinkingText = thinkingText == null ? "" : thinkingText;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if ((publicText == null || publicText.isBlank()) && toolCalls.isEmpty()) {
                throw new IllegalArgumentException("publicText or toolCalls must not be empty");
            }
            Objects.requireNonNull(usage, "usage must not be null");
            Objects.requireNonNull(finishReason, "finishReason must not be null");
            if (latency == null || latency.isNegative()) {
                throw new IllegalArgumentException("latency must not be negative");
            }
            if (attempts < 1 || attempts > MAX_ATTEMPTS) {
                throw new IllegalArgumentException("attempts must be between 1 and " + MAX_ATTEMPTS);
            }
            requireText(traceId, "traceId");
        }

        public ModelResponse(String responseId, String modelName, String publicText, Usage usage,
                             FinishReason finishReason, Duration latency, int attempts, String traceId) {
            this(responseId, modelName, publicText, "", usage, finishReason, latency, attempts, List.of(), traceId);
        }

        public ModelResponse(String responseId, String modelName, String publicText, Usage usage,
                             FinishReason finishReason, Duration latency, int attempts, List<ToolCall> toolCalls, String traceId) {
            this(responseId, modelName, publicText, "", usage, finishReason, latency, attempts, toolCalls, traceId);
        }

        public ModelResponse(String responseId, String modelName, String publicText, String thinkingText, Usage usage,
                             FinishReason finishReason, Duration latency, int attempts, String traceId) {
            this(responseId, modelName, publicText, thinkingText, usage, finishReason, latency, attempts, List.of(), traceId);
        }
    }

    /**
     * A public text delta or terminal usage/tool-call chunk used by AgentScope.
     */
    record ModelStreamChunk(
            String responseId,
            String publicTextDelta,
            Usage usage,
            FinishReason finishReason,
            Duration latency,
            int attempts,
            List<ToolCall> toolCalls,
            String traceId,
            boolean terminal) {

        private static final int MAX_ATTEMPTS = 6;

        public ModelStreamChunk {
            requireText(responseId, "responseId");
            publicTextDelta = publicTextDelta == null ? "" : publicTextDelta;
            usage = usage == null ? new Usage(0, 0, 0) : usage;
            finishReason = finishReason == null ? FinishReason.UNKNOWN : finishReason;
            if (latency == null || latency.isNegative()) {
                throw new IllegalArgumentException("latency must not be negative");
            }
            if (attempts < 1 || attempts > MAX_ATTEMPTS) {
                throw new IllegalArgumentException("attempts must be between 1 and " + MAX_ATTEMPTS);
            }
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            requireText(traceId, "traceId");
        }

        static ModelStreamChunk from(ModelResponse response) {
            Objects.requireNonNull(response, "response must not be null");
            return new ModelStreamChunk(
                    response.responseId(),
                    response.publicText(),
                    response.usage(),
                    response.finishReason(),
                    response.latency(),
                    response.attempts(),
                    response.toolCalls(),
                    response.traceId(),
                    true);
        }
    }

    /**
     * Token accounting provided by the provider response when available.
     *
     * @author zyj
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
     * @author zyj
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
