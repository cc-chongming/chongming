package ai.cc.chongming.review.domain.gateway;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
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
            String traceId) {

        public ModelRequest {
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            Objects.requireNonNull(roleType, "roleType must not be null");
            requireText(profileId, "profileId");
            requireText(promptVersion, "promptVersion");
            requireText(systemInstruction, "systemInstruction");
            Objects.requireNonNull(publicContext, "publicContext must not be null");
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
            requireText(traceId, "traceId");
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
            String traceId) {

        public ModelResponse {
            requireText(responseId, "responseId");
            requireText(modelName, "modelName");
            requireText(publicText, "publicText");
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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
