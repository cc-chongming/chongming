package ai.cc.chongming.review.infrastructure.agentscope.tool;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * [AIREVIEW-PLAN-024] Immutable server-side grant binding one normalized snapshot file to an
 * unguessable {@code fileRef} token.
 *
 * <p>Role agents never see or submit repository paths: they only hold the random {@code fileRef}
 * issued here, and the server resolves it back to {@code reviewId + attemptNo + roleType +
 * snapshotCommit + normalizedPath}. A grant is therefore valid only inside the review, attempt,
 * role and snapshot that issued it.
 *
 * @author wangli
 */
public record RepositoryFileGrant(
        ReviewId reviewId,
        int attemptNo,
        RoleType roleType,
        String snapshotCommit,
        String normalizedPath,
        String fileRef) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FILE_REF_PATTERN = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final int FILE_REF_RANDOM_BYTES = 18;

    public RepositoryFileGrant {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (snapshotCommit == null || snapshotCommit.isBlank()) {
            throw new IllegalArgumentException("snapshotCommit must not be blank");
        }
        normalizedPath = requireNormalizedPath(normalizedPath);
        if (fileRef == null || !FILE_REF_PATTERN.matcher(fileRef).matches()) {
            throw new IllegalArgumentException("fileRef must be an unguessable server-issued token");
        }
    }

    /**
     * Issues a fresh grant with a cryptographically random, unguessable fileRef.
     */
    public static RepositoryFileGrant issue(
            ReviewId reviewId, int attemptNo, RoleType roleType, String snapshotCommit, String normalizedPath) {
        return new RepositoryFileGrant(reviewId, attemptNo, roleType, snapshotCommit, normalizedPath, randomFileRef());
    }

    /**
     * Generates an unguessable fileRef token; callers cannot derive one token from another.
     */
    public static String randomFileRef() {
        byte[] bytes = new byte[FILE_REF_RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Validates and normalizes a snapshot-relative path server-side. This remains a defense-in-depth
     * check even though agents no longer submit paths.
     */
    public static String requireNormalizedPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("normalizedPath must not be blank");
        }
        String value = relativePath.replace('\\', '/');
        if (value.startsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("normalizedPath is unsafe");
        }
        for (String segment : value.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("normalizedPath is unsafe");
            }
        }
        return value;
    }
}
