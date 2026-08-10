package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.repository.ReviewAssessmentStore;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePack.Checkpoint;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-024#方案1] Accepts role-submitted checkpoint assessments, injects server-side
 * identity (review, attempt, role, version and idempotency) before persistence, and exposes the
 * required-checkpoint coverage queries used by the completion guard.
 *
 * <p>Checkpoint conclusions are facts, not debatable claims: repeated submissions for the same
 * checkpoint are idempotent and the latest submission wins.
 *
 * @author wangli
 */
@Service
public class AssessmentService {

    private final ReviewAssessmentStore assessmentStore;
    private final RolePackRegistry rolePackRegistry;

    public AssessmentService(ReviewAssessmentStore assessmentStore, RolePackRegistry rolePackRegistry) {
        this.assessmentStore = Objects.requireNonNull(assessmentStore, "assessmentStore must not be null");
        this.rolePackRegistry = Objects.requireNonNull(rolePackRegistry, "rolePackRegistry must not be null");
    }

    /**
     * Persists one role-submitted assessment after identity, lifecycle, checkpoint-ownership,
     * version and idempotency checks. The model supplies only checkpointKey, status, summary,
     * reasonSummary and evidenceIds; review, attempt, role, version and idempotency are injected
     * server side.
     */
    public AssessmentSubmissionResult submit(Review review, AssessmentSubmission submission) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(submission, "submission must not be null");
        synchronized (review) {
            if (!review.id().equals(submission.metadata().reviewId())) {
                throw new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                        "assessment command reviewId does not match aggregate");
            }
            String existingReference = review.commandResults().get(submission.metadata().idempotencyKey());
            if (existingReference != null) {
                return new AssessmentSubmissionResult(storedAssessment(review, submission.actorRole(),
                        existingReference), true);
            }
            validateReviewAndRole(review, submission.actorRole());
            requireCheckpointOwnership(submission.actorRole(), submission.checkpointKey());
            ReviewAssessment assessment = new ReviewAssessment(
                    review.id(),
                    review.attemptNo(),
                    submission.actorRole(),
                    submission.checkpointKey(),
                    submission.status(),
                    submission.summary(),
                    submission.reasonSummary(),
                    submission.evidenceIds(),
                    ReviewAssessment.idempotencyKeyFor(
                            review.id(), review.attemptNo(), submission.actorRole(), submission.checkpointKey()),
                    Instant.now());
            assessmentStore.saveBatch(review.id(), review.attemptNo(), List.of(assessment));
            review.recordCommand(submission.metadata(), assessment.storageKey());
            return new AssessmentSubmissionResult(assessment, false);
        }
    }

    /**
     * Returns the stable keys of the role's required checkpoints that still lack a current
     * assessment inside this review attempt, deterministically ordered.
     */
    public List<String> missingRequiredCheckpointKeys(ReviewId reviewId, int attemptNo, RoleType roleType) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Set<String> coveredKeys = assessmentStore.findByReview(reviewId, attemptNo, roleType).stream()
                .map(ReviewAssessment::checkpointKey)
                .collect(Collectors.toCollection(TreeSet::new));
        return rolePackRegistry.require(roleType).checklist().stream()
                .filter(Checkpoint::required)
                .filter(Checkpoint::hasStableKey)
                .map(Checkpoint::checkpointKey)
                .filter(key -> !coveredKeys.contains(key))
                .toList();
    }

    /**
     * True when every required stable checkpoint of the role owns exactly one current assessment.
     */
    public boolean isCoverageComplete(ReviewId reviewId, int attemptNo, RoleType roleType) {
        return missingRequiredCheckpointKeys(reviewId, attemptNo, roleType).isEmpty();
    }

    /**
     * Current assessments of one role inside one review attempt.
     */
    public List<ReviewAssessment> findByReview(ReviewId reviewId, int attemptNo, RoleType roleType) {
        return assessmentStore.findByReview(reviewId, attemptNo, roleType);
    }

    /**
     * Server-derived completion summary built from persisted assessments and claims. The model's
     * supplemental summary may be appended but is never the only source of truth.
     */
    public String derivedCompletionSummary(
            ReviewId reviewId, int attemptNo, RoleType roleType, List<Claim> roleClaims, String supplementalSummary) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        List<Claim> claims = roleClaims == null ? List.of() : List.copyOf(roleClaims);
        List<ReviewAssessment> assessments = assessmentStore.findByReview(reviewId, attemptNo, roleType);
        Map<AssessmentStatus, Long> countsByStatus = new LinkedHashMap<>();
        for (AssessmentStatus status : AssessmentStatus.values()) {
            countsByStatus.put(status, 0L);
        }
        assessments.forEach(assessment ->
                countsByStatus.merge(assessment.status(), 1L, Long::sum));
        StringBuilder summary = new StringBuilder(roleType.name())
                .append(" 初审完成（服务端依据已持久化事实派生）。检查点评估 ").append(assessments.size()).append(" 条：");
        summary.append(countsByStatus.entrySet().stream()
                .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                .collect(Collectors.joining("、")));
        summary.append("；风险 Claim ").append(claims.size()).append(" 条。");
        for (ReviewAssessment assessment : assessments) {
            summary.append("\n- ").append(assessment.checkpointKey())
                    .append("：").append(assessment.status().name())
                    .append(" ").append(assessment.summary());
        }
        for (Claim claim : claims) {
            summary.append("\n- Claim[").append(claim.severity().name()).append("/").append(claim.position().name())
                    .append("] ").append(claim.subjectKey()).append("：").append(claim.statement());
        }
        if (supplementalSummary != null && !supplementalSummary.isBlank()) {
            summary.append("\n角色补充：").append(supplementalSummary.trim());
        }
        return summary.toString();
    }

    private ReviewAssessment storedAssessment(Review review, RoleType actorRole, String storageKey) {
        return assessmentStore.findByReview(review.id(), review.attemptNo(), actorRole).stream()
                .filter(assessment -> assessment.storageKey().equals(storageKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("assessment idempotency reference cannot be resolved"));
    }

    private void validateReviewAndRole(Review review, RoleType actorRole) {
        if (review.stage() != ReviewStage.INITIAL_REVIEW) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "assessments may be submitted only during initial review");
        }
        boolean activeRole = review.roleActivations().stream()
                .anyMatch(activation -> activation.roleType() == actorRole);
        if (!activeRole || actorRole == RoleType.DIRECTOR || actorRole == RoleType.JUDGE) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "only an activated review role may submit assessments");
        }
    }

    private void requireCheckpointOwnership(RoleType actorRole, String checkpointKey) {
        RolePack rolePack = rolePackRegistry.require(actorRole);
        boolean owned = rolePack.checklist().stream()
                .filter(Checkpoint::hasStableKey)
                .anyMatch(checkpoint -> checkpoint.checkpointKey().equals(checkpointKey));
        if (!owned) {
            throw new IllegalArgumentException("checkpointKey " + checkpointKey
                    + " is not part of the " + actorRole + " role checklist");
        }
    }

    /**
     * Typed assessment submission boundary; raw model output never reaches the store directly.
     *
     * @author wangli
     */
    public record AssessmentSubmission(
            ReviewCommandMetadata metadata,
            RoleType actorRole,
            String checkpointKey,
            AssessmentStatus status,
            String summary,
            String reasonSummary,
            List<EvidenceId> evidenceIds) {

        public AssessmentSubmission {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            requireText(checkpointKey, "checkpointKey");
            Objects.requireNonNull(status, "status must not be null");
            requireText(summary, "summary");
            if (status.requiresReasonSummary()) {
                requireText(reasonSummary, "reasonSummary is required for status " + status);
            }
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /**
     * Submission output with an explicit idempotent-replay indicator.
     *
     * @author wangli
     */
    public record AssessmentSubmissionResult(ReviewAssessment assessment, boolean replayed) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
