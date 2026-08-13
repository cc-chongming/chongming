package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * [AIREVIEW-PLAN-024#方案5] Batch mapper for durable conflict audit facts.
 *
 * @author zyj
 */
@Mapper
public interface ReviewConflictAuditPersistenceMapper {

    @Delete("""
            DELETE FROM review_conflict_audit
            WHERE review_id = #{reviewId} AND attempt_no = #{attemptNo}
            """)
    int deleteByAttempt(@Param("reviewId") String reviewId, @Param("attemptNo") int attemptNo);

    @Insert("""
            <script>
            INSERT INTO review_conflict_audit
                (review_id, attempt_no, subject_hash, subject_key, claim_ids_json, rules,
                 disposition, updated_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.reviewId}, #{row.attemptNo}, #{row.subjectHash}, #{row.subjectKey},
                 #{row.claimIdsJson}, #{row.rules}, #{row.disposition}, #{row.updatedAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<ConflictAuditRow> rows);

    @Update("""
            <script>
            UPDATE review_conflict_audit
            SET disposition =
                <choose>
                    <when test="registeredSubjectHashes != null and registeredSubjectHashes.size() > 0">
                        CASE WHEN subject_hash IN
                        <foreach collection="registeredSubjectHashes" item="subjectHash" open="(" separator="," close=")">
                            #{subjectHash}
                        </foreach>
                        THEN 'REGISTERED' ELSE 'SKIPPED' END
                    </when>
                    <otherwise>'SKIPPED'</otherwise>
                </choose>,
                updated_at = #{updatedAt}
            WHERE review_id = #{reviewId}
              AND attempt_no = #{attemptNo}
              AND disposition = 'DETECTED'
            </script>
            """)
    int finalizeDetected(
            @Param("reviewId") String reviewId,
            @Param("attemptNo") int attemptNo,
            @Param("registeredSubjectHashes") List<String> registeredSubjectHashes,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT review_id AS reviewId, attempt_no AS attemptNo, subject_hash AS subjectHash,
                   subject_key AS subjectKey, claim_ids_json AS claimIdsJson, rules, disposition,
                   updated_at AS updatedAt
            FROM review_conflict_audit
            WHERE review_id = #{reviewId} AND attempt_no = #{attemptNo}
            ORDER BY subject_key
            """)
    List<ConflictAuditRow> findByAttempt(
            @Param("reviewId") String reviewId, @Param("attemptNo") int attemptNo);

    /**
     * @author zyj
     */
    record ConflictAuditRow(
            String reviewId,
            int attemptNo,
            String subjectHash,
            String subjectKey,
            String claimIdsJson,
            String rules,
            String disposition,
            LocalDateTime updatedAt) {
    }
}
