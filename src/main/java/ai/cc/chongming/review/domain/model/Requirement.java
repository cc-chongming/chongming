package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.protocol.RequirementLifecycleStateMachine;
import java.time.Instant;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-021#1] Owns requirement metadata and lifecycle state across one or more review attempts.
 *
 * @author zyj
 */
public final class Requirement {

    private final RequirementId id;
    private String title;
    private String description;
    private final String creatorId;
    private String assigneeId;
    private String repositoryPath;
    private String priority;
    private RequirementStatus status;
    private ReviewId reviewId;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Requirement(
            RequirementId id,
            String title,
            String description,
            String creatorId,
            String assigneeId,
            String repositoryPath,
            String priority,
            RequirementStatus status,
            ReviewId reviewId,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = required(title, "title");
        this.description = description == null ? "" : description;
        this.creatorId = required(creatorId, "creatorId");
        this.assigneeId = normalizeOptional(assigneeId);
        this.repositoryPath = normalizeOptional(repositoryPath);
        this.priority = normalizeOptional(priority);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.reviewId = reviewId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
    }

    public static Requirement draft(
            RequirementId id,
            String title,
            String description,
            String creatorId,
            String assigneeId,
            String repositoryPath,
            String priority) {
        Instant now = Instant.now();
        return new Requirement(
                id,
                title,
                description,
                creatorId,
                assigneeId,
                repositoryPath,
                priority,
                RequirementStatus.DRAFT,
                null,
                now,
                now,
                0L);
    }

    public static Requirement restore(
            RequirementId id,
            String title,
            String description,
            String creatorId,
            String assigneeId,
            String repositoryPath,
            String priority,
            RequirementStatus status,
            ReviewId reviewId,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Requirement(
                id,
                title,
                description,
                creatorId,
                assigneeId,
                repositoryPath,
                priority,
                status,
                reviewId,
                createdAt,
                updatedAt,
                version);
    }

    public RequirementId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String creatorId() {
        return creatorId;
    }

    public String assigneeId() {
        return assigneeId;
    }

    public String repositoryPath() {
        return repositoryPath;
    }

    public String priority() {
        return priority;
    }

    public RequirementStatus status() {
        return status;
    }

    public ReviewId reviewId() {
        return reviewId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public void revise(
            String title,
            String description,
            String assigneeId,
            String repositoryPath,
            String priority,
            long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (status != RequirementStatus.DRAFT && status != RequirementStatus.RETURNED) {
            throw new RequirementDomainException(
                    RequirementErrorCode.ILLEGAL_LIFECYCLE_TRANSITION,
                    "only draft or returned requirements can be revised");
        }
        this.title = required(title, "title");
        this.description = description == null ? "" : description;
        this.assigneeId = normalizeOptional(assigneeId);
        this.repositoryPath = normalizeOptional(repositoryPath);
        this.priority = normalizeOptional(priority);
        touch();
    }

    public void submitForReview(ReviewId reviewId, RequirementLifecycleStateMachine stateMachine) {
        validateSubmissionForReview(reviewId, stateMachine);
        ReviewId targetReviewId = reviewId;
        RequirementStatus nextStatus = stateMachine.transition(status, RequirementStatus.PENDING_REVIEW);
        this.reviewId = targetReviewId;
        this.status = nextStatus;
        touch();
    }

    /**
     * [AIREVIEW-PLAN-021#2][REQLIFE-H1] Checks a submission before the review-side reservation mutates state.
     */
    public void validateSubmissionForReview(ReviewId reviewId, RequirementLifecycleStateMachine stateMachine) {
        ReviewId targetReviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (this.reviewId != null && !this.reviewId.equals(targetReviewId) && status != RequirementStatus.RETURNED) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REVIEW_ALREADY_BOUND, "a requirement can only bind one active review");
        }
        Objects.requireNonNull(stateMachine, "stateMachine must not be null")
                .transition(status, RequirementStatus.PENDING_REVIEW);
    }

    public void transitionTo(RequirementStatus nextStatus, RequirementLifecycleStateMachine stateMachine) {
        status = Objects.requireNonNull(stateMachine, "stateMachine must not be null")
                .transition(status, Objects.requireNonNull(nextStatus, "nextStatus must not be null"));
        touch();
    }

    public void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new RequirementDomainException(
                    RequirementErrorCode.VERSION_CONFLICT,
                    "expectedVersion does not match requirement version");
        }
    }

    private void touch() {
        updatedAt = Instant.now();
        version++;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
