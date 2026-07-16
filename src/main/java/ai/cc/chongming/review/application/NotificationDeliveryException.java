package ai.cc.chongming.review.application;

/**
 * [AIREVIEW-PLAN-011#1.5,#1.6] Normalized transport failure without exposing credentials or remote response bodies.
 *
 * @author wangli
 */
public class NotificationDeliveryException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public NotificationDeliveryException(String code, boolean retryable, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
        this.retryable = retryable;
    }

    public NotificationDeliveryException(String code, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
