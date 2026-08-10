package ai.cc.chongming.review.infrastructure.model;

import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelProfile;

import java.net.URI;
import java.util.Objects;

import reactor.core.publisher.Flux;

/**
 * Provider wire-protocol port used by the commercial gateway and replaceable by deterministic tests.
 * <p>
 * [AIREVIEW-PLAN-023#8]
 *
 * @author zyj
 */
public interface ModelProviderClient {

    /**
     * Invokes one provider request using a server-resolved credential.
     *
     * @param request provider invocation details
     * @return normalized provider response
     */
    ProviderResponse invoke(ProviderRequest request);

    /**
     * Streams provider chunks when the concrete provider supports it. Implementations that do not
     * expose a streaming transport deliberately fall back to one terminal chunk; callers can
     * therefore distinguish a real multi-chunk stream from compatibility mode without inventing
     * client-side tokenization.
     */
    default Flux<ProviderStreamChunk> stream(ProviderRequest request) {
        return Flux.defer(() -> Flux.just(ProviderStreamChunk.from(invoke(request))));
    }

    /**
     * Provider-specific invocation input. This type is never supplied by an API caller.
     *
     * @author zyj
     */
    record ProviderRequest(
            URI baseUrl,
            String apiKey,
            ModelProfile profile,
            ModelGateway.ModelRequest request,
            boolean logConversation) {

        public ProviderRequest {
            Objects.requireNonNull(baseUrl, "baseUrl must not be null");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }
            Objects.requireNonNull(profile, "profile must not be null");
            Objects.requireNonNull(request, "request must not be null");
        }
    }

    /**
     * Provider response normalized before audit and workflow handling.
     *
     * @author zyj
     */
    record ProviderResponse(
            String responseId,
            String publicText,
            String thinkingText,
            ModelGateway.Usage usage,
            ModelGateway.FinishReason finishReason,
            java.util.List<ModelGateway.ToolCall> toolCalls) {

        public ProviderResponse {
            if (responseId == null || responseId.isBlank()) {
                throw new IllegalArgumentException("responseId must not be blank");
            }
            publicText = publicText == null ? "" : publicText;
            thinkingText = thinkingText == null ? "" : thinkingText;
            toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
            if ((publicText == null || publicText.isBlank()) && toolCalls.isEmpty()) {
                throw new IllegalArgumentException("publicText or toolCalls must not be empty");
            }
            Objects.requireNonNull(usage, "usage must not be null");
            Objects.requireNonNull(finishReason, "finishReason must not be null");
        }

        public ProviderResponse(String responseId, String publicText, ModelGateway.Usage usage,
                                ModelGateway.FinishReason finishReason) {
            this(responseId, publicText, "", usage, finishReason, java.util.List.of());
        }

        public ProviderResponse(String responseId, String publicText, ModelGateway.Usage usage,
                                ModelGateway.FinishReason finishReason, java.util.List<ModelGateway.ToolCall> toolCalls) {
            this(responseId, publicText, "", usage, finishReason, toolCalls);
        }
    }

    /**
     * One public provider delta or the terminal metadata/tool-call chunk.
     */
    record ProviderStreamChunk(
            String responseId,
            String publicTextDelta,
            ModelGateway.Usage usage,
            ModelGateway.FinishReason finishReason,
            java.util.List<ModelGateway.ToolCall> toolCalls,
            boolean terminal) {

        public ProviderStreamChunk {
            if (responseId == null || responseId.isBlank()) {
                throw new IllegalArgumentException("responseId must not be blank");
            }
            publicTextDelta = publicTextDelta == null ? "" : publicTextDelta;
            usage = usage == null ? new ModelGateway.Usage(0, 0, 0) : usage;
            finishReason = finishReason == null ? ModelGateway.FinishReason.UNKNOWN : finishReason;
            toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
        }

        static ProviderStreamChunk from(ProviderResponse response) {
            Objects.requireNonNull(response, "response must not be null");
            return new ProviderStreamChunk(
                    response.responseId(),
                    response.publicText(),
                    response.usage(),
                    response.finishReason(),
                    response.toolCalls(),
                    true);
        }
    }
}
