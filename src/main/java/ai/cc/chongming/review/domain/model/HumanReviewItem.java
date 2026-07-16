package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.1] Versioned, soft-deletable human review draft.
 *
 * @author wangli
 */
public record HumanReviewItem(
        UUID itemId,
        ReviewId reviewId,
        ItemType type,
        ClaimSeverity severity,
        String title,
        String content,
        List<UUID> claimIds,
        List<EvidenceId> evidenceIds,
        String action,
        long version,
        ItemStatus status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    private static final int MAX_TITLE_LENGTH = 256;
    private static final int MAX_CONTENT_LENGTH = 8_000;
    private static final int MAX_REFERENCE_COUNT = 100;

    public HumanReviewItem {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        title = requireText(title, "title", MAX_TITLE_LENGTH);
        content = requireText(content, "content", MAX_CONTENT_LENGTH);
        claimIds = copyIds(claimIds, "claimIds");
        evidenceIds = copyEvidenceIds(evidenceIds);
        action = requireText(action, "action", MAX_TITLE_LENGTH);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(status, "status must not be null");
        createdBy = requireText(createdBy, "createdBy", 128);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public static HumanReviewItem draft(ReviewId reviewId, String reviewerId, DraftContent content, Instant now) {
        Objects.requireNonNull(content, "content must not be null");
        return new HumanReviewItem(
                UUID.randomUUID(),
                reviewId,
                content.type(),
                content.severity(),
                content.title(),
                content.content(),
                content.claimIds(),
                content.evidenceIds(),
                content.action(),
                0L,
                ItemStatus.DRAFT,
                reviewerId,
                now,
                now);
    }

    public HumanReviewItem revise(DraftContent content, long expectedVersion, Instant now) {
        requireEditable(expectedVersion);
        Objects.requireNonNull(content, "content must not be null");
        return new HumanReviewItem(
                itemId, reviewId, content.type(), content.severity(), content.title(), content.content(),
                content.claimIds(), content.evidenceIds(), content.action(), version + 1,
                ItemStatus.DRAFT, createdBy, createdAt, Objects.requireNonNull(now, "now must not be null"));
    }

    public HumanReviewItem delete(long expectedVersion, Instant now) {
        requireEditable(expectedVersion);
        return new HumanReviewItem(
                itemId, reviewId, type, severity, title, content, claimIds, evidenceIds, action, version + 1,
                ItemStatus.DELETED, createdBy, createdAt, Objects.requireNonNull(now, "now must not be null"));
    }

    private void requireEditable(long expectedVersion) {
        if (status != ItemStatus.DRAFT) {
            throw new IllegalStateException("only draft human review items can be changed");
        }
        if (expectedVersion != version) {
            throw new IllegalStateException("expectedVersion does not match human review item version");
        }
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be non-blank and within " + maxLength + " characters");
        }
        return value;
    }

    private static List<UUID> copyIds(List<UUID> values, String name) {
        List<UUID> copy = List.copyOf(values == null ? List.of() : values);
        if (copy.size() > MAX_REFERENCE_COUNT || copy.stream().anyMatch(Objects::isNull)
                || new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " must contain at most " + MAX_REFERENCE_COUNT + " distinct IDs");
        }
        return copy;
    }

    private static List<EvidenceId> copyEvidenceIds(List<EvidenceId> values) {
        List<EvidenceId> copy = List.copyOf(values == null ? List.of() : values);
        if (copy.size() > MAX_REFERENCE_COUNT || copy.stream().anyMatch(Objects::isNull)
                || new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("evidenceIds must contain at most " + MAX_REFERENCE_COUNT + " distinct IDs");
        }
        return copy;
    }

    /**
     * @author wangli
     */
    public record DraftContent(
            ItemType type,
            ClaimSeverity severity,
            String title,
            String content,
            List<UUID> claimIds,
            List<EvidenceId> evidenceIds,
            String action) {

        public DraftContent {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(severity, "severity must not be null");
            claimIds = List.copyOf(claimIds == null ? List.of() : claimIds);
            evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        }
    }

    /**
     * @author wangli
     */
    public enum ItemType {
        RISK,
        REQUIREMENT,
        EVIDENCE,
        GATE
    }

    /**
     * @author wangli
     */
    public enum ItemStatus {
        DRAFT,
        DELETED
    }
}
