package ai.cc.chongming.review.domain.event;

/**
 * [AIREVIEW-PLAN-010#1.1] Enumerates immutable business facts, distinct from raw AgentScope telemetry.
 *
 * @author wangli
 */
public enum ReviewEventType {
    REVIEW_ACCEPTED(ReviewEventCategory.PLAN),
    PLAN_CREATED(ReviewEventCategory.PLAN),
    PLAN_REVISED(ReviewEventCategory.PLAN),
    ROLE_ACTIVATED(ReviewEventCategory.ROLE),
    ROLE_STARTED(ReviewEventCategory.ROLE),
    ROLE_COMPLETED(ReviewEventCategory.ROLE),
    ROLE_FAILED(ReviewEventCategory.ERROR),
    EVIDENCE_CAPTURED(ReviewEventCategory.EVIDENCE),
    CLAIM_SUBMITTED(ReviewEventCategory.CLAIM),
    DEBATE_TOPIC_OPENED(ReviewEventCategory.DEBATE),
    CHALLENGE_SUBMITTED(ReviewEventCategory.DEBATE),
    REBUTTAL_SUBMITTED(ReviewEventCategory.DEBATE),
    POSITION_CHANGED(ReviewEventCategory.DEBATE),
    EVIDENCE_REQUESTED(ReviewEventCategory.DEBATE),
    DEBATE_TOPIC_CLOSED(ReviewEventCategory.DEBATE),
    JUDGEMENT_SUBMITTED(ReviewEventCategory.JUDGEMENT),
    GATE_DRAFTED(ReviewEventCategory.GATE),
    HUMAN_REVIEW_REQUIRED(ReviewEventCategory.HUMAN),
    REVIEW_CANCELLED(ReviewEventCategory.ERROR),
    REVIEW_RETRIED(ReviewEventCategory.PLAN),
    REVIEW_RECOVERED(ReviewEventCategory.PLAN),
    REVIEW_FAILED(ReviewEventCategory.ERROR);

    private final ReviewEventCategory category;

    ReviewEventType(ReviewEventCategory category) {
        this.category = category;
    }

    public ReviewEventCategory category() {
        return category;
    }
}
