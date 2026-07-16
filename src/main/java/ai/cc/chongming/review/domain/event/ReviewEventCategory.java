package ai.cc.chongming.review.domain.event;

/**
 * [AIREVIEW-PLAN-010#1.1] Stable categories for business events exposed to read models and SSE clients.
 *
 * @author wangli
 */
public enum ReviewEventCategory {
    PLAN,
    ROLE,
    CLAIM,
    EVIDENCE,
    DEBATE,
    JUDGEMENT,
    HUMAN,
    GATE,
    NOTIFICATION,
    ERROR
}
