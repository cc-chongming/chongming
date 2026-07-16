package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;

/**
 * [AIREVIEW-PLAN-011#1.5,#1.6] Isolates Outbox retry semantics from a concrete notification transport.
 *
 * @author wangli
 */
public interface NotificationDeliveryPort {

    NotificationDeliveryReceipt deliver(NotificationCommand command) throws NotificationDeliveryException;
}
