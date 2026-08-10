package ai.cc.chongming.review.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * [AIREVIEW-PLAN-023#3] Describes stable, phase-aware failures from the draft launch orchestration.
 *
 * @author zyj
 */
public class RequirementReviewLaunchException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final String phase;
    private final boolean recoverable;
    private final UUID existingReviewId;

    public RequirementReviewLaunchException(
            String code,
            HttpStatus status,
            String message,
            String phase,
            boolean recoverable,
            UUID existingReviewId) {
        super(message);
        this.code = code;
        this.status = status;
        this.phase = phase;
        this.recoverable = recoverable;
        this.existingReviewId = existingReviewId;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String phase() {
        return phase;
    }

    public boolean recoverable() {
        return recoverable;
    }

    public UUID existingReviewId() {
        return existingReviewId;
    }

    public static RequirementReviewLaunchException invalidPublicTasks() {
        return new RequirementReviewLaunchException(
                "INVALID_PUBLIC_TASKS",
                HttpStatus.BAD_REQUEST,
                "publicTasks must be a non-empty JSON string array",
                "INTAKE",
                false,
                null);
    }

    static RequirementReviewLaunchException unreadableUpload() {
        return new RequirementReviewLaunchException(
                "UNREADABLE_UPLOAD",
                HttpStatus.BAD_REQUEST,
                "Unable to fingerprint uploaded Markdown file",
                "INTAKE",
                false,
                null);
    }

    static RequirementReviewLaunchException idempotencyKeyReused(UUID existingReviewId) {
        return new RequirementReviewLaunchException(
                "IDEMPOTENCY_KEY_REUSED",
                HttpStatus.CONFLICT,
                "Idempotency-Key was already used with another launch request",
                "INTAKE",
                false,
                existingReviewId);
    }

    static RequirementReviewLaunchException launchInProgress() {
        return new RequirementReviewLaunchException(
                "REVIEW_LAUNCH_IN_PROGRESS",
                HttpStatus.CONFLICT,
                "The same launch command is already being processed; retry with the same request",
                "INTAKE",
                true,
                null);
    }

    static RequirementReviewLaunchException alreadyBound(UUID existingReviewId) {
        return new RequirementReviewLaunchException(
                "REVIEW_ALREADY_BOUND",
                HttpStatus.CONFLICT,
                "requirement is already bound to another review",
                "INTAKE",
                false,
                existingReviewId);
    }

    static RequirementReviewLaunchException reviewBindingConflict(UUID reviewId) {
        return new RequirementReviewLaunchException(
                "REVIEW_ALREADY_BOUND",
                HttpStatus.CONFLICT,
                "review is already bound to another requirement",
                "INTAKE",
                false,
                reviewId);
    }

    static RequirementReviewLaunchException startFailed(UUID reviewId, RuntimeException cause) {
        RequirementReviewLaunchException exception = new RequirementReviewLaunchException(
                "REVIEW_START_FAILED",
                HttpStatus.CONFLICT,
                "review was bound but could not be started; retry the same launch command",
                "BOUND",
                true,
                reviewId);
        exception.initCause(cause);
        return exception;
    }
}
