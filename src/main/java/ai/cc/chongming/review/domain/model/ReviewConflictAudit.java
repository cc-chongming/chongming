package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-024#方案5] Durable one-to-one audit fact for a conflict subject.
 *
 * @author zyj
 */
public record ReviewConflictAudit(
        ReviewId reviewId,
        int attemptNo,
        String subjectKey,
        List<ClaimId> claimIds,
        String rules,
        Disposition disposition,
        Instant updatedAt) {

    public ReviewConflictAudit {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        subjectKey = normalizeSubjectKey(subjectKey);
        claimIds = List.copyOf(Objects.requireNonNull(claimIds, "claimIds must not be null"));
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public String subjectHash() {
        return subjectHashFor(subjectKey);
    }

    public static String subjectHashFor(String subjectKey) {
        String normalizedSubjectKey = normalizeSubjectKey(subjectKey);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedSubjectKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    public ReviewConflictAudit withDisposition(Disposition nextDisposition, Instant changedAt) {
        return new ReviewConflictAudit(
                reviewId, attemptNo, subjectKey, claimIds, rules, nextDisposition, changedAt);
    }

    public static String normalizeSubjectKey(String subjectKey) {
        Objects.requireNonNull(subjectKey, "subjectKey must not be null");
        String normalized = subjectKey.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("subjectKey must not be blank");
        }
        return normalized;
    }

    /**
     * @author zyj
     */
    public enum Disposition {
        DETECTED,
        REGISTERED,
        SKIPPED,
        NO_CONFLICT
    }
}
