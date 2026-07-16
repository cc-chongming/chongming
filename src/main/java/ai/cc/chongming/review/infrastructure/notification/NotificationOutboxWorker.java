package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.application.NotificationDeliveryPort;
import ai.cc.chongming.review.application.NotificationOutboxService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-011#1.5,#1.6] Runs bounded asynchronous notification dispatch only when explicitly enabled.
 *
 * @author wangli
 */
@Component
@ConditionalOnProperty(prefix = "review.notification", name = "worker-enabled", havingValue = "true")
public class NotificationOutboxWorker {

    private static final int DISPATCH_BATCH_SIZE = 50;

    private final NotificationOutboxService outboxService;
    private final NotificationDeliveryPort deliveryPort;

    public NotificationOutboxWorker(NotificationOutboxService outboxService, NotificationDeliveryPort deliveryPort) {
        this.outboxService = outboxService;
        this.deliveryPort = deliveryPort;
    }

    @Scheduled(fixedDelayString = "${review.notification.worker-delay:PT5S}")
    public void dispatchDueNotifications() {
        outboxService.dispatchDue(deliveryPort, DISPATCH_BATCH_SIZE);
    }
}
