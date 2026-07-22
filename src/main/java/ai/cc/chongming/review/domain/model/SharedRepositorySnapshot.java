package ai.cc.chongming.review.domain.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata for a content-addressed, server-managed repository snapshot shared by reviews.
 *
 * @author wangli
 */
public record SharedRepositorySnapshot(
        String snapshotKey,
        String repositoryId,
        String headCommit,
        String branch,
        boolean dirty,
        String worktreeFingerprint,
        String manifestHash,
        long includedFileCount,
        Instant createdAt,
        Instant lastAccessedAt,
        Path repositoryRoot) {

    public SharedRepositorySnapshot {
        requireHash(snapshotKey, "snapshotKey");
        requireText(repositoryId, "repositoryId");
        requireText(headCommit, "headCommit");
        requireText(branch, "branch");
        requireFingerprint(worktreeFingerprint);
        requireHash(manifestHash, "manifestHash");
        if (includedFileCount < 0) {
            throw new IllegalArgumentException("includedFileCount must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(lastAccessedAt, "lastAccessedAt must not be null");
        Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
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

    private static void requireFingerprint(String value) {
        requireText(value, "worktreeFingerprint");
        if (!"clean".equals(value) && !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("worktreeFingerprint must be clean or a lower-case SHA-256 hash");
        }
    }
}
