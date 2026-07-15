package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Batch-oriented MyBatis statements for review aggregates and their evidence links.
 *
 * @author wangli
 */
@Mapper
public interface ReviewPersistenceMapper {

    @Select("""
            SELECT review_id AS reviewId, stage, current_attempt_no AS attemptNo, version
            FROM review_request WHERE review_id = #{reviewId}
            """)
    ReviewRow findReview(@Param("reviewId") String reviewId);

    @Update("""
            UPDATE review_request
            SET stage = #{review.stage}, current_attempt_no = #{review.attemptNo}, version = #{review.version}
            WHERE review_id = #{review.reviewId} AND version = #{expectedVersion}
            """)
    int updateReview(@Param("review") ReviewRow row, @Param("expectedVersion") long expectedVersion);

    @Select("""
            SELECT activation_id AS activationId, review_id AS reviewId, attempt_no AS attemptNo,
                   role_code AS roleCode, agent_name AS agentName, status
            FROM role_activation WHERE review_id = #{reviewId}
            ORDER BY created_at, activation_id
            """)
    List<RoleActivationRow> findRoleActivations(@Param("reviewId") String reviewId);

    @Insert("""
            INSERT IGNORE INTO role_activation
                (activation_id, review_id, attempt_no, role_code, agent_name, status)
            VALUES (#{activationId}, #{reviewId}, #{attemptNo}, #{roleCode}, #{agentName}, #{status})
            """)
    int insertRoleActivation(RoleActivationRow row);

    @Select("""
            SELECT review_id AS reviewId, idempotency_key AS idempotencyKey, result_reference AS resultReference
            FROM review_command_result WHERE review_id = #{reviewId}
            """)
    List<CommandResultRow> findCommandResults(@Param("reviewId") String reviewId);

    @Select("""
            SELECT result_reference FROM review_command_result
            WHERE review_id = #{reviewId} AND idempotency_key = #{idempotencyKey}
            """)
    String findCommandResult(
            @Param("reviewId") String reviewId, @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT IGNORE INTO review_command_result (review_id, idempotency_key, result_reference)
            VALUES (#{reviewId}, #{idempotencyKey}, #{resultReference})
            """)
    int insertCommandResult(CommandResultRow row);

    @Select("""
            <script>
            SELECT claim_id AS claimId, review_id AS reviewId, role_type AS roleType,
                   subject_key AS subjectKey, severity, position, statement_text AS statement,
                   reason_summary AS reasonSummary, status
            FROM claim WHERE review_id = #{reviewId} AND claim_id IN
            <foreach item="claimId" collection="claimIds" open="(" separator="," close=")">
                #{claimId}
            </foreach>
            </script>
            """)
    List<ClaimRow> findClaimsByIds(
            @Param("reviewId") String reviewId, @Param("claimIds") Set<String> claimIds);

    @Select("""
            <script>
            SELECT ce.claim_id AS claimId, eb.evidence_id AS evidenceId, eb.snapshot_id AS snapshotId,
                   eb.relative_path AS relativePath, eb.line_number AS lineNumber,
                   eb.snippet_hash AS snippetHash
            FROM claim_evidence ce JOIN evidence_block eb ON eb.evidence_id = ce.evidence_id
            WHERE ce.claim_id IN
            <foreach item="claimId" collection="claimIds" open="(" separator="," close=")">
                #{claimId}
            </foreach>
            </script>
            """)
    List<ClaimEvidenceRow> findEvidenceByClaimIds(@Param("claimIds") Set<String> claimIds);

    @Select("""
            <script>
            SELECT evidence_id AS evidenceId, snapshot_id AS snapshotId, relative_path AS relativePath,
                   line_number AS lineNumber, snippet_hash AS snippetHash
            FROM evidence_block WHERE review_id = #{reviewId} AND evidence_id IN
            <foreach item="evidenceId" collection="evidenceIds" open="(" separator="," close=")">
                #{evidenceId}
            </foreach>
            </script>
            """)
    List<EvidenceRow> findEvidenceByIds(
            @Param("reviewId") String reviewId, @Param("evidenceIds") Set<String> evidenceIds);

    @Select("""
            <script>
            SELECT dt.turn_id AS turnId, dt.topic_id AS topicId, dt.turn_no AS round,
                   dt.actor_role AS actorRole, dt.target_role AS targetRole, dt.turn_type AS turnType,
                   dt.target_claim_id AS targetClaimId, dt.target_turn_id AS targetTurnId,
                   dt.public_content AS publicContent, dt.stance_before AS stanceBefore,
                   dt.stance_after AS stanceAfter, dt.created_at AS createdAt
            FROM debate_turn dt JOIN debate_topic topic ON topic.topic_id = dt.topic_id
            WHERE topic.review_id = #{reviewId} AND dt.turn_id IN
            <foreach item="turnId" collection="turnIds" open="(" separator="," close=")">
                #{turnId}
            </foreach>
            </script>
            """)
    List<DebateTurnRow> findTurnsByIds(
            @Param("reviewId") String reviewId, @Param("turnIds") Set<String> turnIds);

    @Select("""
            <script>
            SELECT turn_id AS turnId, evidence_id AS evidenceId FROM debate_turn_evidence
            WHERE turn_id IN
            <foreach item="turnId" collection="turnIds" open="(" separator="," close=")">
                #{turnId}
            </foreach>
            </script>
            """)
    List<TurnEvidenceRow> findEvidenceByTurnIds(@Param("turnIds") Set<String> turnIds);

    /**
     * Row for the mutable review root.
     *
     * @author wangli
     */
    record ReviewRow(String reviewId, String stage, int attemptNo, long version) {
    }

    /**
     * Row for role activation history.
     *
     * @author wangli
     */
    record RoleActivationRow(
            String activationId, String reviewId, int attemptNo, String roleCode, String agentName, String status) {
    }

    /**
     * Row for idempotent command outcomes.
     *
     * @author wangli
     */
    record CommandResultRow(String reviewId, String idempotencyKey, String resultReference) {
    }

    /**
     * Row for a claim without its batch-loaded evidence references.
     *
     * @author wangli
     */
    record ClaimRow(
            String claimId,
            String reviewId,
            String roleType,
            String subjectKey,
            String severity,
            String position,
            String statement,
            String reasonSummary,
            String status) {
    }

    /**
     * Row for a claim-to-evidence relation.
     *
     * @author wangli
     */
    record ClaimEvidenceRow(
            String claimId,
            String evidenceId,
            String snapshotId,
            String relativePath,
            int lineNumber,
            String snippetHash) {
    }

    /**
     * Row for a reusable evidence reference.
     *
     * @author wangli
     */
    record EvidenceRow(String evidenceId, String snapshotId, String relativePath, int lineNumber, String snippetHash) {
    }

    /**
     * Row for a debate turn without its batch-loaded evidence identifiers.
     *
     * @author wangli
     */
    record DebateTurnRow(
            String turnId,
            String topicId,
            int round,
            String actorRole,
            String targetRole,
            String turnType,
            String targetClaimId,
            String targetTurnId,
            String publicContent,
            String stanceBefore,
            String stanceAfter,
            LocalDateTime createdAt) {
    }

    /**
     * Row for a debate-turn-to-evidence relation.
     *
     * @author wangli
     */
    record TurnEvidenceRow(String turnId, String evidenceId) {
    }
}
