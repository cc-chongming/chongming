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
            ModelGateway.ModelRequest request) {

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
            ModelGateway.Usage usage,
            ModelGateway.FinishReason finishReason) {

        public ProviderResponse {
            if (responseId == null || responseId.isBlank()) {
                throw new IllegalArgumentException("responseId must not be blank");
            }
            if (publicText == null || publicText.isBlank()) {
                throw new IllegalArgumentException("publicText must not be blank");
            }
            Objects.requireNonNull(usage, "usage must not be null");
            Objects.requireNonNull(finishReason, "finishReason must not be null");
        }
    }
}
