package ai.cc.chongming.review.domain.event;

/**
 * [AIREVIEW-PLAN-010#1.1][AIREVIEW-PLAN-011#1.1,#1.5] Enumerates immutable business facts, distinct from raw AgentScope telemetry.
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
    CONTEXT_SCOUT_DEGRADED(ReviewEventCategory.ERROR),
    INITIAL_REVIEW_COMPLETED(ReviewEventCategory.PLAN),
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
    HUMAN_REVIEW_ITEM_CREATED(ReviewEventCategory.HUMAN),
    HUMAN_REVIEW_ITEM_UPDATED(ReviewEventCategory.HUMAN),
    HUMAN_REVIEW_ITEM_DELETED(ReviewEventCategory.HUMAN),
    HUMAN_GATE_FINALIZED(ReviewEventCategory.HUMAN),
    NOTIFICATION_QUEUED(ReviewEventCategory.NOTIFICATION),
    NOTIFICATION_SENT(ReviewEventCategory.NOTIFICATION),
    NOTIFICATION_FAILED(ReviewEventCategory.NOTIFICATION),
    NOTIFICATION_DEAD(ReviewEventCategory.NOTIFICATION),
    NOTIFICATION_RETRY_REQUESTED(ReviewEventCategory.NOTIFICATION),
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
