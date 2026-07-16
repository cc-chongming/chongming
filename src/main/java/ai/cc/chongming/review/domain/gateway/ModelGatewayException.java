package ai.cc.chongming.review.domain.gateway;

import java.util.Objects;

/**
 * Stable failure contract for model calls, safe to audit without exposing provider credentials.
 *
 * @author wangli
 */
public class ModelGatewayException extends RuntimeException {

    private final Code code;

    public ModelGatewayException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public ModelGatewayException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public Code code() {
        return code;
    }

    /**
     * Caller-safe model failure categories.
     *
     * @author wangli
     */
    public enum Code {
        MODEL_GATEWAY_DISABLED,
        MODEL_PROFILE_NOT_FOUND,
        MODEL_CANCELLED,
        MODEL_CALL_TIMEOUT,
        MODEL_RATE_LIMITED,
        MODEL_NETWORK_ERROR,
        MODEL_PROVIDER_ERROR,
        MODEL_RESPONSE_INVALID
    }
}
