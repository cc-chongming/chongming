package ai.cc.chongming.review.domain.model;

/**
 * [AIREVIEW-PLAN-011#1.5] Sanitized result returned by a notification adapter.
 *
 * @author wangli
 */
public record NotificationDeliveryReceipt(String responseCode, String responseHash) {

    public NotificationDeliveryReceipt {
        if (responseCode == null || responseCode.isBlank()) {
            throw new IllegalArgumentException("responseCode must not be blank");
        }
        if (responseHash == null || responseHash.isBlank()) {
            throw new IllegalArgumentException("responseHash must not be blank");
        }
    }
}
