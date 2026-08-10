package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * [AIREVIEW-PLAN-011#1.3] Persists and reloads immutable final human Gate versions.
 *
 * @author wangli
 */
@Mapper
public interface HumanGateDecisionPersistenceMapper {

    @Insert("""
            INSERT INTO human_gate_decision
                (gate_decision_id, review_id, gate_version, gate_result, reason_text, conditions_json,
                 override_reason, reviewer_id, supersedes_version, decided_at)
            VALUES
                (#{row.gateDecisionId}, #{row.reviewId}, #{row.gateVersion}, #{row.gateResult}, #{row.reasonText},
                 #{row.conditionsJson}, #{row.overrideReason}, #{row.reviewerId}, #{row.supersedesVersion}, #{row.decidedAt})
            """)
    int insert(@Param("row") HumanGateDecisionRow row);

    @Select("""
            SELECT gate_decision_id AS gateDecisionId, review_id AS reviewId, gate_version AS gateVersion,
                   gate_result AS gateResult, reason_text AS reasonText, conditions_json AS conditionsJson,
                   override_reason AS overrideReason, reviewer_id AS reviewerId, supersedes_version AS supersedesVersion,
                   decided_at AS decidedAt
            FROM human_gate_decision
            WHERE review_id = #{reviewId}
            ORDER BY gate_version DESC
            LIMIT 1
            """)
    HumanGateDecisionRow findLatest(@Param("reviewId") String reviewId);

    @Select("""
            SELECT gate_decision_id AS gateDecisionId, review_id AS reviewId, gate_version AS gateVersion,
                   gate_result AS gateResult, reason_text AS reasonText, conditions_json AS conditionsJson,
                   override_reason AS overrideReason, reviewer_id AS reviewerId, supersedes_version AS supersedesVersion,
                   decided_at AS decidedAt
            FROM human_gate_decision
            WHERE review_id = #{reviewId}
            ORDER BY gate_version
            """)
    List<HumanGateDecisionRow> findVersions(@Param("reviewId") String reviewId);

    /**
     * @author wangli
     */
    record HumanGateDecisionRow(
            String gateDecisionId,
            String reviewId,
            long gateVersion,
            String gateResult,
            String reasonText,
            String conditionsJson,
            String overrideReason,
            String reviewerId,
            Long supersedesVersion,
            LocalDateTime decidedAt) {
    }
}
