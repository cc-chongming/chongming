package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;

/**
 * [AIREVIEW-PLAN-011#1.6] Deployment-supplied client for a separately verified Learning Platform MCP contract.
 *
 * @author wangli
 */
public interface LearningPlatformMcpClient {

    NotificationDeliveryReceipt deliver(LearningPlatformMcpRequest request);
}
