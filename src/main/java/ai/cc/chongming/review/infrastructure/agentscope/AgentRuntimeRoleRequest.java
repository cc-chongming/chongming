package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.util.Objects;

/**
 * Project-authorized request to create one isolated role runtime inside an active review attempt.
 *
 * @author wangli
 */
public record AgentRuntimeRoleRequest(
        String runtimeId,
        ReviewRuntimeContext runtimeContext,
        RoleType roleType,
        String label,
        String sessionId) {

    public AgentRuntimeRoleRequest {
        requireText(runtimeId, "runtimeId");
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (roleType == RoleType.DIRECTOR) {
            throw new IllegalArgumentException("director must not be registered as a role runtime");
        }
        requireText(label, "label");
        requireText(sessionId, "sessionId");
        if (!runtimeId.equals(runtimeContext.runtimeId())
                || !label.equals(runtimeContext.roleLabel(roleType))
                || !sessionId.equals(runtimeContext.roleSessionId(roleType))) {
            throw new IllegalArgumentException("role runtime request identity must be derived from runtime context");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
