package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, filesystem-backed repository state frozen for one review.
 *
 * @author wangli
 */
public record RepositorySnapshot(
        UUID snapshotId,
        ReviewId reviewId,
        String repositoryId,
        Path sourceRepositoryRoot,
        Path snapshotRepositoryRoot,
        String headCommit,
        String branch,
        boolean dirty,
        String manifestHash,
        long includedFileCount,
        Instant capturedAt) {

    public RepositorySnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        requireText(repositoryId, "repositoryId");
        Objects.requireNonNull(sourceRepositoryRoot, "sourceRepositoryRoot must not be null");
        Objects.requireNonNull(snapshotRepositoryRoot, "snapshotRepositoryRoot must not be null");
        requireText(headCommit, "headCommit");
        requireText(branch, "branch");
        requireHash(manifestHash, "manifestHash");
        if (includedFileCount < 0) {
            throw new IllegalArgumentException("includedFileCount must not be negative");
        }
        Objects.requireNonNull(capturedAt, "capturedAt must not be null");
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
