package ai.cc.chongming.review.domain.exception;

import java.util.Objects;

/**
 * [AIREVIEW-PLAN-003#1.1] Carries a stable domain error code without exposing infrastructure details.
 *
 * @author wangli
 */
public final class ReviewDomainException extends RuntimeException {

    private final ReviewErrorCode errorCode;

    public ReviewDomainException(ReviewErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public ReviewErrorCode errorCode() {
        return errorCode;
    }
}

