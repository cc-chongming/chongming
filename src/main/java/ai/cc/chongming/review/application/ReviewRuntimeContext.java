package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.util.Locale;
import java.util.Objects;

/**
 * Explicit, per-attempt metadata supplied to every director and role runtime call.
 *
 * @author wangli
 */
public record ReviewRuntimeContext(
        ReviewId reviewId,
        int attemptNo,
        String userId,
        String traceId,
        IntakeCancellation cancellation) {

    public ReviewRuntimeContext {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        requireText(userId, "userId");
        requireText(traceId, "traceId");
        cancellation = cancellation == null ? IntakeCancellation.neverCancelled() : cancellation;
    }

    /**
     * Stable runtime ID shared by the director and all role agents for one review attempt.
     */
    public String runtimeId() {
        return "review-" + reviewId.value() + "-attempt-" + attemptNo;
    }

    /**
     * Stable director agent label reused by all messages in the current attempt.
     */
    public String directorLabel() {
        return runtimeId() + "-director";
    }

    /**
     * Stable director session ID; restart recovery must reuse it verbatim.
     */
    public String directorSessionId() {
        return directorLabel() + "-session";
    }

    /**
     * Derives a role-specific, attempt-stable agent label without exposing caller-provided input.
     */
    public String roleLabel(RoleType roleType) {
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (roleType == RoleType.DIRECTOR) {
            throw new IllegalArgumentException("director is not a role subagent");
        }
        return runtimeId() + "-" + roleType.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Derives the persistent session key for exactly one role in this attempt.
     */
    public String roleSessionId(RoleType roleType) {
        return roleLabel(roleType) + "-session";
    }

    /**
     * Replaces the cooperative cancellation signal without changing identity fields.
     */
    public ReviewRuntimeContext withCancellation(IntakeCancellation replacement) {
        return new ReviewRuntimeContext(reviewId, attemptNo, userId, traceId, replacement);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
