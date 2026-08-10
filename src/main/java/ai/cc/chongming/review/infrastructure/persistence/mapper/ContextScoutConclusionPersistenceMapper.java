package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * [AIREVIEW-PLAN-023#5] Persists one public Context Scout conclusion per review attempt.
 *
 * @author zyj
 */
@Mapper
public interface ContextScoutConclusionPersistenceMapper {

    @Insert("""
            INSERT INTO context_scout_conclusion
                (review_id, attempt_no, schema_version, summary_text, module_roots_json, entry_points_json,
                 constraints_json, risks_json, evidence_paths_json, role_scopes_json, raw_public_result, created_at)
            VALUES
                (#{row.reviewId}, #{row.attemptNo}, #{row.schemaVersion}, #{row.summaryText}, #{row.moduleRootsJson},
                 #{row.entryPointsJson}, #{row.constraintsJson}, #{row.risksJson}, #{row.evidencePathsJson},
                 #{row.roleScopesJson}, #{row.rawPublicResult}, #{row.createdAt})
            ON DUPLICATE KEY UPDATE
                schema_version = VALUES(schema_version),
                summary_text = VALUES(summary_text),
                module_roots_json = VALUES(module_roots_json),
                entry_points_json = VALUES(entry_points_json),
                constraints_json = VALUES(constraints_json),
                risks_json = VALUES(risks_json),
                evidence_paths_json = VALUES(evidence_paths_json),
                role_scopes_json = VALUES(role_scopes_json),
                raw_public_result = VALUES(raw_public_result),
                created_at = VALUES(created_at)
            """)
    int save(@Param("row") ContextScoutConclusionRow row);

    @Select("""
            SELECT review_id AS reviewId, attempt_no AS attemptNo, schema_version AS schemaVersion,
                   summary_text AS summaryText, module_roots_json AS moduleRootsJson,
                   entry_points_json AS entryPointsJson, constraints_json AS constraintsJson,
                   risks_json AS risksJson, evidence_paths_json AS evidencePathsJson,
                   role_scopes_json AS roleScopesJson, raw_public_result AS rawPublicResult,
                   created_at AS createdAt
            FROM context_scout_conclusion
            WHERE review_id = #{reviewId} AND attempt_no = #{attemptNo}
            """)
    ContextScoutConclusionRow find(@Param("reviewId") String reviewId, @Param("attemptNo") int attemptNo);

    /**
     * [AIREVIEW-PLAN-023#5] MyBatis row with JSON stored as MySQL 5.6-compatible LONGTEXT.
     *
     * @author zyj
     */
    record ContextScoutConclusionRow(
            String reviewId,
            int attemptNo,
            int schemaVersion,
            String summaryText,
            String moduleRootsJson,
            String entryPointsJson,
            String constraintsJson,
            String risksJson,
            String evidencePathsJson,
            String roleScopesJson,
            String rawPublicResult,
            LocalDateTime createdAt) {
    }
}
