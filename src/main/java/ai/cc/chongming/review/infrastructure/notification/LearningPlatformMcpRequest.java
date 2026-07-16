package ai.cc.chongming.review.infrastructure.notification;

import java.util.List;
import java.util.Map;

/**
 * [AIREVIEW-PLAN-011#1.6] Semantic envelope passed to the deployment-owned MCP client, not an asserted remote schema.
 *
 * @author wangli
 */
public record LearningPlatformMcpRequest(
        String idempotencyKey,
        Map<String, String> attributes,
        List<String> conditions) {

    public LearningPlatformMcpRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }
}
