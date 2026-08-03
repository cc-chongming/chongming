package ai.cc.chongming.review.domain.exception;

import java.util.Objects;

/**
 * [AIREVIEW-PLAN-021#1] Carries a stable requirement-domain error code.
 *
 * @author zyj
 */
public final class RequirementDomainException extends RuntimeException {

    private final RequirementErrorCode errorCode;

    public RequirementDomainException(RequirementErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public RequirementErrorCode errorCode() {
        return errorCode;
    }
}
