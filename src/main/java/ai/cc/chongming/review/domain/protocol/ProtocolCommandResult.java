package ai.cc.chongming.review.domain.protocol;

/**
 * [AIREVIEW-PLAN-003#1.5,#1.6] Describes a validated command result and its future domain-event type.
 *
 * @author wangli
 */
public record ProtocolCommandResult(String resultReference, String eventType) {
}

