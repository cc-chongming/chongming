package ai.cc.chongming.review.infrastructure.agentscope.tool;

import java.util.List;
import java.util.Objects;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * Strongly typed, server-validated inputs for debate operations; no agent may send raw persistence payloads.
 *
 * @author wangli
 */
public final class DebateToolCommands {

    private DebateToolCommands() {
    }

    /** @author wangli */
    public record OpenTopic(ReviewCommandMetadata metadata, RoleType actorRole, String subjectKey, List<ClaimId> claimIds) {
        public OpenTopic {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            requireText(subjectKey, "subjectKey");
            claimIds = copyNonEmpty(claimIds, "claimIds");
            if (claimIds.size() < 1) {
                throw new IllegalArgumentException("a topic requires at least one claim");
            }
        }
    }

    /** @author wangli */
    public record Challenge(
            ReviewCommandMetadata metadata,
            RoleType actorRole,
            RoleType targetRole,
            TopicId topicId,
            int round,
            ClaimId targetClaimId,
            String publicContent,
            List<EvidenceId> evidenceIds,
            String evidenceGap) {
        public Challenge {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(targetRole, "targetRole must not be null");
            Objects.requireNonNull(topicId, "topicId must not be null");
            Objects.requireNonNull(targetClaimId, "targetClaimId must not be null");
            requireRound(round);
            requireText(publicContent, "publicContent");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            evidenceGap = evidenceGap == null ? "" : evidenceGap.trim();
            if (evidenceIds.isEmpty() && evidenceGap.isBlank()) {
                throw new IllegalArgumentException("challenge requires evidenceIds or an explicit evidenceGap");
            }
        }
    }

    /** @author wangli */
    public record Rebuttal(
            ReviewCommandMetadata metadata,
            RoleType actorRole,
            RoleType targetRole,
            TopicId topicId,
            int round,
            TurnId targetTurnId,
            String publicContent,
            List<EvidenceId> evidenceIds) {
        public Rebuttal {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(targetRole, "targetRole must not be null");
            Objects.requireNonNull(topicId, "topicId must not be null");
            Objects.requireNonNull(targetTurnId, "targetTurnId must not be null");
            requireRound(round);
            requireText(publicContent, "publicContent");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /** @author wangli */
    public record PositionChange(
            ReviewCommandMetadata metadata,
            RoleType actorRole,
            TopicId topicId,
            int round,
            ClaimId targetClaimId,
            ClaimPosition stanceAfter,
            String publicContent,
            List<EvidenceId> evidenceIds) {
        public PositionChange {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(topicId, "topicId must not be null");
            Objects.requireNonNull(targetClaimId, "targetClaimId must not be null");
            Objects.requireNonNull(stanceAfter, "stanceAfter must not be null");
            requireRound(round);
            requireText(publicContent, "publicContent");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /** @author wangli */
    public record EvidenceRequest(
            ReviewCommandMetadata metadata,
            RoleType actorRole,
            RoleType targetRole,
            TopicId topicId,
            int round,
            ClaimId targetClaimId,
            String publicContent) {
        public EvidenceRequest {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(targetRole, "targetRole must not be null");
            Objects.requireNonNull(topicId, "topicId must not be null");
            Objects.requireNonNull(targetClaimId, "targetClaimId must not be null");
            requireRound(round);
            requireText(publicContent, "publicContent");
        }
    }

    /** @author wangli */
    public record CloseTopic(
            ReviewCommandMetadata metadata,
            TopicId topicId,
            DebateTopicStatus status,
            String publicResolution) {
        public CloseTopic {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(topicId, "topicId must not be null");
            if (status == null || !status.isTerminal()) {
                throw new IllegalArgumentException("status must be terminal");
            }
            requireText(publicResolution, "publicResolution");
        }
    }

    private static void requireRound(int round) {
        if (round < 1 || round > 2) {
            throw new IllegalArgumentException("round must be between 1 and 2");
        }
    }

    private static <T> List<T> copyNonEmpty(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(values);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
