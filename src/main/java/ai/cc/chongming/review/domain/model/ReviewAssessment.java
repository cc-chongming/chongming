package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * [AIREVIEW-PLAN-024#方案0] Structured, checkpoint-scoped assessment conclusion submitted by a review role.
 * An assessment separates confirmed facts, partial satisfaction, gaps and unknowns from debatable Claims,
 * so positive conclusions are no longer disguised as SUPPORT Claims.
 *
 * @author wangli
 */
public record ReviewAssessment(
        ReviewId reviewId,
        int attemptNo,
        RoleType roleType,
        String checkpointKey,
        AssessmentStatus status,
        String summary,
        String reasonSummary,
        List<EvidenceId> evidenceIds,
        IdempotencyKey idempotencyKey,
        Instant createdAt) {

    private static final Pattern STABLE_CHECKPOINT_KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]+)*");

    public ReviewAssessment {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(roleType, "roleType must not be null");
        requireText(checkpointKey, "checkpointKey");
        if (!STABLE_CHECKPOINT_KEY.matcher(checkpointKey).matches()) {
            throw new IllegalArgumentException(
                    "checkpointKey must be a stable lower-snake-case identifier: " + checkpointKey);
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        requireText(summary, "summary");
        if (status.requiresReasonSummary()) {
            requireText(reasonSummary, "reasonSummary is required for status " + status);
        }
        evidenceIds = List.copyOf(evidenceIds);
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Server-side identity of this assessment inside one review attempt; repeated submissions with the
     * same identity are idempotent and the latest submission wins.
     */
    public String storageKey() {
        return roleType + ":" + checkpointKey;
    }

    /**
     * Computes the default server-side idempotency key for one checkpoint assessment.
     */
    public static IdempotencyKey idempotencyKeyFor(
            ReviewId reviewId, int attemptNo, RoleType roleType, String checkpointKey) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        requireText(checkpointKey, "checkpointKey");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        return new IdempotencyKey(reviewId.value() + ":" + attemptNo + ":" + roleType + ":" + checkpointKey);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
