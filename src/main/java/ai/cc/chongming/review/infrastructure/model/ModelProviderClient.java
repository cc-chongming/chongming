package ai.cc.chongming.review.infrastructure.model;

import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelProfile;
import java.net.URI;
import java.util.Objects;

/**
 * Provider wire-protocol port used by the commercial gateway and replaceable by deterministic tests.
 *
 * @author wangli
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
     * Provider-specific invocation input. This type is never supplied by an API caller.
     *
     * @author wangli
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
     * @author wangli
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
}
