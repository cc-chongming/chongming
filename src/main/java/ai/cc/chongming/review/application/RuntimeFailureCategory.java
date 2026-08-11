package ai.cc.chongming.review.application;

/**
 * [AIREVIEW-PLAN-024#6] Independent runtime failure categories for a review attempt. Each
 * category is counted on its own; they must never be aggregated into a single generic
 * "model degradation" bucket so a full review can explain exactly where time and degradation
 * happened.
 *
 * @author wangli
 */
public enum RuntimeFailureCategory {

    /** Model call exceeded its configured timeout ({@code MODEL_CALL_TIMEOUT}). */
    MODEL_TIMEOUT,

    /** Model returned a response that is not valid JSON or has no usable public content. */
    NON_JSON_RESPONSE,

    /** A tool invocation was rejected because its parameters failed validation. */
    TOOL_PARAMETER_REJECTED,

    /** Repository access was rejected because the caller lacks the grant for the target. */
    REPOSITORY_ACCESS_DENIED,

    /** The role's bounded repository read budget is exhausted. */
    READ_BUDGET_EXHAUSTED
}
