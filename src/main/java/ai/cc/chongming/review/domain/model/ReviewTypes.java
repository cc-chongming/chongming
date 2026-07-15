package ai.cc.chongming.review.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-003#1.1,#1.2,#1.6] Defines the stable value types shared by the review domain.
 *
 * @author wangli
 */
public final class ReviewTypes {

    private ReviewTypes() {
    }

    /**
     * @author wangli
     */
    public record ReviewId(UUID value) {
        public ReviewId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * @author wangli
     */
    public record ClaimId(UUID value) {
        public ClaimId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * @author wangli
     */
    public record EvidenceId(UUID value) {
        public EvidenceId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * @author wangli
     */
    public record TopicId(UUID value) {
        public TopicId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * @author wangli
     */
    public record TurnId(UUID value) {
        public TurnId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * @author wangli
     */
    public enum ReviewStage {
        PENDING,
        SNAPSHOTTING,
        PLANNING,
        INITIAL_REVIEW,
        CONFLICT_DETECTION,
        DEBATE_ROUND_1,
        DEBATE_ROUND_2,
        JUDGING,
        WAITING_HUMAN,
        NOTIFYING,
        COMPLETED,
        CANCELLING,
        CANCELLED,
        FAILED;

        public boolean isTerminal() {
            return this == COMPLETED || this == CANCELLED || this == FAILED;
        }
    }

    /**
     * @author wangli
     */
    public enum RoleType {
        PRODUCT,
        PROJECT,
        FRONTEND,
        BACKEND,
        SECURITY,
        ARCHITECTURE,
        TESTING,
        PERFORMANCE,
        JUDGE;

        public boolean isCore() {
            return this == PRODUCT || this == PROJECT || this == FRONTEND || this == BACKEND;
        }

        public boolean isOptional() {
            return !isCore() && this != JUDGE;
        }
    }

    /**
     * @author wangli
     */
    public enum ClaimSeverity {
        P0,
        P1,
        P2,
        P3;

        public boolean requiresEvidenceForAutoBlock() {
            return this == P0 || this == P1;
        }
    }

    /**
     * @author wangli
     */
    public enum ClaimPosition {
        SUPPORT,
        OPPOSE,
        NEUTRAL
    }

    /**
     * @author wangli
     */
    public enum ClaimStatus {
        SUBMITTED,
        UNVERIFIED,
        WITHDRAWN
    }

    /**
     * @author wangli
     */
    public enum DebateTurnType {
        CHALLENGE,
        REBUTTAL,
        CONCESSION,
        POSITION_CHANGE,
        JUDGEMENT
    }

    /**
     * @author wangli
     */
    public enum DebateTopicStatus {
        OPEN,
        CHALLENGED,
        REBUTTED,
        RESOLVED,
        ESCALATED;

        public boolean isTerminal() {
            return this == RESOLVED || this == ESCALATED;
        }
    }

    /**
     * @author wangli
     */
    public enum GateResult {
        AI_PASS,
        CONDITIONAL,
        BLOCK,
        RETURN,
        HUMAN_REQUIRED,
        PASS,
        OVERRIDE
    }

    /**
     * @author wangli
     */
    public enum DecisionStatus {
        DRAFT,
        FINAL
    }

    /**
     * @author wangli
     */
    public enum DecisionActor {
        AI,
        HUMAN
    }

    /**
     * @author wangli
     */
    public record RoleActivation(RoleType roleType, String agentLabel, boolean initialReviewCompleted) {
        public RoleActivation {
            Objects.requireNonNull(roleType, "roleType must not be null");
            requireText(agentLabel, "agentLabel");
        }
    }

    /**
     * @author wangli
     */
    public record EvidenceReference(
            EvidenceId evidenceId,
            String snapshotId,
            String relativePath,
            int lineNumber,
            String snippetHash) {
        public EvidenceReference {
            Objects.requireNonNull(evidenceId, "evidenceId must not be null");
            requireText(snapshotId, "snapshotId");
            requireText(relativePath, "relativePath");
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be positive");
            }
            requireText(snippetHash, "snippetHash");
        }
    }

    /**
     * @author wangli
     */
    public record IdempotencyKey(String value) {
        public IdempotencyKey {
            requireText(value, "value");
        }

        public static IdempotencyKey of(
                ReviewId reviewId,
                TopicId topicId,
                int round,
                RoleType actorRole,
                DebateTurnType turnType) {
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(turnType, "turnType must not be null");
            if (round < 0) {
                throw new IllegalArgumentException("round must not be negative");
            }
            String topicPart = topicId == null ? "-" : topicId.value().toString();
            return new IdempotencyKey(reviewId.value() + ":" + topicPart + ":" + round + ":" + actorRole + ":" + turnType);
        }
    }

    /**
     * @author wangli
     */
    public record ReviewCommandMetadata(ReviewId reviewId, long expectedVersion, IdempotencyKey idempotencyKey) {
        public ReviewCommandMetadata {
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
        }
    }

    /**
     * @author wangli
     */
    public record ReviewPlan(
            ReviewId reviewId,
            int planVersion,
            List<String> publicTasks,
            String changeReason,
            String changedBy,
            Instant createdAt) {
        public ReviewPlan {
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            if (planVersion < 1) {
                throw new IllegalArgumentException("planVersion must be positive");
            }
            publicTasks = List.copyOf(publicTasks);
            requireText(changeReason, "changeReason");
            requireText(changedBy, "changedBy");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    /**
     * @author wangli
     */
    public record DebateTurn(
            TurnId turnId,
            TopicId topicId,
            int round,
            RoleType actorRole,
            RoleType targetRole,
            DebateTurnType turnType,
            ClaimId targetClaimId,
            TurnId targetTurnId,
            String publicContent,
            List<EvidenceId> evidenceIds,
            ClaimPosition stanceBefore,
            ClaimPosition stanceAfter,
            Instant createdAt) {
        public DebateTurn {
            Objects.requireNonNull(turnId, "turnId must not be null");
            Objects.requireNonNull(topicId, "topicId must not be null");
            if (round < 1) {
                throw new IllegalArgumentException("round must be positive");
            }
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(turnType, "turnType must not be null");
            requireText(publicContent, "publicContent");
            evidenceIds = List.copyOf(evidenceIds);
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    /**
     * @author wangli
     */
    public record JudgeDecision(TopicId topicId, GateResult result, String publicReasonSummary, Instant createdAt) {
        public JudgeDecision {
            Objects.requireNonNull(topicId, "topicId must not be null");
            Objects.requireNonNull(result, "result must not be null");
            requireText(publicReasonSummary, "publicReasonSummary");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    /**
     * @author wangli
     */
    public record HumanReviewItem(UUID itemId, ReviewId reviewId, String publicSummary, DecisionStatus status) {
        public HumanReviewItem {
            Objects.requireNonNull(itemId, "itemId must not be null");
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            requireText(publicSummary, "publicSummary");
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

