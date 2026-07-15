package ai.cc.chongming.review.api.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Accepted response returned after a Markdown requirement snapshot is created or replayed.
 *
 * @author wangli
 */
public record CreateReviewResponse(
        UUID reviewId, int attempt, String snapshotHash, String statusUrl, boolean reused) {

    public CreateReviewResponse {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        requireText(snapshotHash, "snapshotHash");
        requireText(statusUrl, "statusUrl");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
