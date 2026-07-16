package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, line-addressable code evidence derived only from a frozen repository snapshot.
 *
 * @author wangli
 */
public record EvidenceBlock(
        EvidenceId evidenceId,
        ReviewId reviewId,
        UUID repositorySnapshotId,
        String repoRevision,
        String sourceAbsolutePath,
        String snapshotRelativePath,
        int lineNumber,
        String excerpt,
        String excerptHash,
        String fileHash,
        Instant createdAt) {

    public EvidenceBlock {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(repositorySnapshotId, "repositorySnapshotId must not be null");
        requireText(repoRevision, "repoRevision");
        requireText(sourceAbsolutePath, "sourceAbsolutePath");
        requireText(snapshotRelativePath, "snapshotRelativePath");
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
        requireText(excerpt, "excerpt");
        requireHash(excerptHash, "excerptHash");
        requireHash(fileHash, "fileHash");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
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
