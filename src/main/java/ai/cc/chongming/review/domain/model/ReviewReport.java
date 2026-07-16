package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.4] Immutable, versioned public review report snapshot.
 *
 * @author wangli
 */
public record ReviewReport(
        UUID reportId,
        ReviewId reviewId,
        long reportVersion,
        long gateVersion,
        String contentHash,
        String contentJson,
        String markdown,
        Instant createdAt) {

    public ReviewReport {
        Objects.requireNonNull(reportId, "reportId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (reportVersion < 1) {
            throw new IllegalArgumentException("reportVersion must be positive");
        }
        if (gateVersion < 1) {
            throw new IllegalArgumentException("gateVersion must be positive");
        }
        contentHash = requireText(contentHash, "contentHash");
        contentJson = requireText(contentJson, "contentJson");
        markdown = requireText(markdown, "markdown");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
