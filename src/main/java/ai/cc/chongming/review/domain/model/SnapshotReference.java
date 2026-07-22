package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.time.Instant;
import java.util.Objects;

/**
 * Review-owned reference to a server-managed shared repository snapshot.
 *
 * @author wangli
 */
public record SnapshotReference(
        ReviewId reviewId,
        int attemptNo,
        String snapshotKey,
        String repositoryId,
        Instant boundAt,
        String requirementSnapshotHash) {

    public SnapshotReference {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        requireHash(snapshotKey, "snapshotKey");
        requireText(repositoryId, "repositoryId");
        Objects.requireNonNull(boundAt, "boundAt must not be null");
        requireHash(requirementSnapshotHash, "requirementSnapshotHash");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireHash(String value, String name) {
        requireText(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lower-case SHA-256 hash");
        }
    }
}
