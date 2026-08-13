package ai.cc.chongming.review.domain.exception;

/**
 * [AIREVIEW-PLAN-021#1] Stable error codes for requirement lifecycle commands.
 *
 * @author zyj
 */
public enum RequirementErrorCode {
    REQUIREMENT_NOT_FOUND,
    ILLEGAL_LIFECYCLE_TRANSITION,
    REVIEW_ALREADY_BOUND,
    VERSION_CONFLICT,
    REQUIREMENT_HAS_ACTIVE_TASK
}
