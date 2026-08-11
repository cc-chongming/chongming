package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * [AIREVIEW-PLAN-024#方案5] Persists and reloads directed dispatch commands. Issuance is idempotent
 * on idempotency_key (unique key, INSERT IGNORE) and status transitions update the stored row in
 * place. Every read fetches by review/attempt batch or unique key; no per-row loops.
 *
 * @author wangli
 */
@Mapper
public interface ReviewDispatchPersistenceMapper {

    @Insert("""
            INSERT IGNORE INTO review_dispatch_command
                (command_id, review_id, attempt_no, stage, round_no, recipient_role, allowed_action,
                 topic_id, target_claim_id, target_turn_id, expires_at, status, idempotency_key, created_at)
            VALUES
                (#{row.commandId}, #{row.reviewId}, #{row.attemptNo}, #{row.stage}, #{row.round},
                 #{row.recipientRole}, #{row.allowedAction}, #{row.topicId}, #{row.targetClaimId},
                 #{row.targetTurnId}, #{row.expiresAt}, #{row.status}, #{row.idempotencyKey}, #{row.createdAt})
            """)
    int insertIgnore(@Param("row") DispatchCommandRow row);

    @Update("""
            UPDATE review_dispatch_command
            SET status = #{row.status}
            WHERE review_id = #{row.reviewId} AND command_id = #{row.commandId}
            """)
    int updateStatus(@Param("row") DispatchCommandRow row);

    @Select("""
            SELECT command_id AS commandId, review_id AS reviewId, attempt_no AS attemptNo, stage,
                   round_no AS round, recipient_role AS recipientRole, allowed_action AS allowedAction,
                   topic_id AS topicId, target_claim_id AS targetClaimId, target_turn_id AS targetTurnId,
                   expires_at AS expiresAt, status, idempotency_key AS idempotencyKey, created_at AS createdAt
            FROM review_dispatch_command
            WHERE review_id = #{reviewId} AND command_id = #{commandId}
            """)
    DispatchCommandRow findById(@Param("reviewId") String reviewId, @Param("commandId") String commandId);

    @Select("""
            SELECT command_id AS commandId, review_id AS reviewId, attempt_no AS attemptNo, stage,
                   round_no AS round, recipient_role AS recipientRole, allowed_action AS allowedAction,
                   topic_id AS topicId, target_claim_id AS targetClaimId, target_turn_id AS targetTurnId,
                   expires_at AS expiresAt, status, idempotency_key AS idempotencyKey, created_at AS createdAt
            FROM review_dispatch_command
            WHERE review_id = #{reviewId} AND idempotency_key = #{idempotencyKey}
            """)
    DispatchCommandRow findByIdempotencyKey(
            @Param("reviewId") String reviewId, @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT command_id AS commandId, review_id AS reviewId, attempt_no AS attemptNo, stage,
                   round_no AS round, recipient_role AS recipientRole, allowed_action AS allowedAction,
                   topic_id AS topicId, target_claim_id AS targetClaimId, target_turn_id AS targetTurnId,
                   expires_at AS expiresAt, status, idempotency_key AS idempotencyKey, created_at AS createdAt
            FROM review_dispatch_command
            WHERE review_id = #{reviewId} AND attempt_no = #{attemptNo}
            ORDER BY created_at, command_id
            """)
    List<DispatchCommandRow> findByAttempt(@Param("reviewId") String reviewId, @Param("attemptNo") int attemptNo);

    @Select("""
            SELECT command_id AS commandId, review_id AS reviewId, attempt_no AS attemptNo, stage,
                   round_no AS round, recipient_role AS recipientRole, allowed_action AS allowedAction,
                   topic_id AS topicId, target_claim_id AS targetClaimId, target_turn_id AS targetTurnId,
                   expires_at AS expiresAt, status, idempotency_key AS idempotencyKey, created_at AS createdAt
            FROM review_dispatch_command
            WHERE review_id = #{reviewId} AND attempt_no = #{attemptNo} AND status = 'PENDING'
            ORDER BY created_at, command_id
            """)
    List<DispatchCommandRow> findPendingByAttempt(@Param("reviewId") String reviewId, @Param("attemptNo") int attemptNo);

    @Select("""
            SELECT command_id AS commandId, review_id AS reviewId, attempt_no AS attemptNo, stage,
                   round_no AS round, recipient_role AS recipientRole, allowed_action AS allowedAction,
                   topic_id AS topicId, target_claim_id AS targetClaimId, target_turn_id AS targetTurnId,
                   expires_at AS expiresAt, status, idempotency_key AS idempotencyKey, created_at AS createdAt
            FROM review_dispatch_command
            WHERE review_id = #{reviewId} AND attempt_no = #{attemptNo}
              AND status = 'PENDING' AND recipient_role = #{recipientRole}
            ORDER BY created_at, command_id
            """)
    List<DispatchCommandRow> findPendingByAttemptAndRecipient(
            @Param("reviewId") String reviewId,
            @Param("attemptNo") int attemptNo,
            @Param("recipientRole") String recipientRole);

    /**
     * @author wangli
     */
    record DispatchCommandRow(
            String commandId,
            String reviewId,
            int attemptNo,
            String stage,
            int round,
            String recipientRole,
            String allowedAction,
            String topicId,
            String targetClaimId,
            String targetTurnId,
            LocalDateTime expiresAt,
            String status,
            String idempotencyKey,
            LocalDateTime createdAt) {
    }
}
