package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.infrastructure.document.StoredRequirementSnapshot;
import java.util.Objects;

/**
 * Result of a successful Markdown intake, including the immutable snapshot identity.
 *
 * @author wangli
 */
public record ReviewIntakeResult(
        RequirementSnapshot snapshot, StoredRequirementSnapshot workspaceSnapshot, boolean reused) {

    public ReviewIntakeResult {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(workspaceSnapshot, "workspaceSnapshot must not be null");
    }
}
