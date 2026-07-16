package ai.cc.chongming.review.infrastructure.agentscope.tool;

import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.util.Objects;

/**
 * Server-issued identity and frozen-snapshot scope required by every repository tool call.
 *
 * @author wangli
 */
public record RepositoryToolContext(
        String runtimeId,
        ReviewId reviewId,
        RoleType roleType,
        RepositorySnapshot snapshot) {

    public RepositoryToolContext {
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtimeId must not be blank");
        }
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!reviewId.equals(snapshot.reviewId())) {
            throw new IllegalArgumentException("Tool context reviewId must match the repository snapshot");
        }
    }
}
