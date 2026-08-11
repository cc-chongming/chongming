package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * [AIREVIEW-PLAN-024#方案5] Persists and reloads five-status checkpoint assessments. Writes are
 * batch upserts (latest submission wins per review/attempt/role/checkpointKey); reads always fetch
 * a complete attempt batch in one statement so callers never query per checkpoint.
 *
 * @author wangli
 */
@Mapper
public interface ReviewAssessmentPersistenceMapper {

    @Insert("""
            <script>
            INSERT INTO review_assessment
                (review_id, attempt_no, role_type, checkpoint_key, status, summary, reason_summary,
                 evidence_ids_json, idempotency_key, created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.reviewId}, #{row.attemptNo}, #{row.roleType}, #{row.checkpointKey}, #{row.status},
                 #{row.summary}, #{row.reasonSummary}, #{row.evidenceIdsJson}, #{row.idempotencyKey},
                 #{row.createdAt})
            </foreach>
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                summary = VALUES(summary),
                reason_summary = VALUES(reason_summary),
                evidence_ids_json = VALUES(evidence_ids_json),
                idempotency_key = VALUES(idempotency_key),
                created_at = VALUES(created_at)
            </script>
            """)
    int upsertBatch(@Param("rows") List<AssessmentRow> rows);

    @Select("""
            SELECT review_id AS reviewId, attempt_no AS attemptNo, role_type AS roleType,
                   checkpoint_key AS checkpointKey, status, summary, reason_summary AS reasonSummary,
                   evidence_ids_json AS evidenceIdsJson, idempotency_key AS idempotencyKey,
                   created_at AS createdAt
            FROM review_assessment
            WHERE review_id = #{reviewId} AND attempt_no = #{attemptNo}
            ORDER BY role_type, checkpoint_key
            """)
    List<AssessmentRow> findByAttempt(@Param("reviewId") String reviewId, @Param("attemptNo") int attemptNo);

    @Select("""
            SELECT review_id AS reviewId, attempt_no AS attemptNo, role_type AS roleType,
                   checkpoint_key AS checkpointKey, status, summary, reason_summary AS reasonSummary,
                   evidence_ids_json AS evidenceIdsJson, idempotency_key AS idempotencyKey,
                   created_at AS createdAt
            FROM review_assessment
            WHERE review_id = #{reviewId} AND attempt_no = #{attemptNo} AND role_type = #{roleType}
            ORDER BY checkpoint_key
            """)
    List<AssessmentRow> findByAttemptAndRole(
            @Param("reviewId") String reviewId,
            @Param("attemptNo") int attemptNo,
            @Param("roleType") String roleType);

    /**
     * @author wangli
     */
    record AssessmentRow(
            String reviewId,
            int attemptNo,
            String roleType,
            String checkpointKey,
            String status,
            String summary,
            String reasonSummary,
            String evidenceIdsJson,
            String idempotencyKey,
            LocalDateTime createdAt) {
    }
}
