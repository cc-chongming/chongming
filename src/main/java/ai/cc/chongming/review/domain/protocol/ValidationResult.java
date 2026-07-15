package ai.cc.chongming.review.domain.protocol;

import ai.cc.chongming.review.domain.exception.ReviewErrorCode;

/**
 * [AIREVIEW-PLAN-003#1.5] Returns a side-effect-free protocol validation outcome.
 *
 * @author wangli
 */
public record ValidationResult(boolean isValid, ReviewErrorCode errorCode) {

    public static ValidationResult valid() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult invalid(ReviewErrorCode errorCode) {
        return new ValidationResult(false, errorCode);
    }
}

