package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H2] MySQL read model for exact platform review pagination.
 *
 * @author zyj
 */
@Mapper
public interface ReviewPlatformProjectionMapper {

    @Select("""
            SELECT root.review_id AS reviewId, root.stage, root.current_attempt_no AS attemptNo,
                   root.version AS reviewVersion, root.updated_at AS updatedAt,
                   evt.event_id AS eventId, evt.attempt_no AS eventAttemptNo,
                   evt.event_sequence AS sequence, evt.event_type AS eventType, evt.event_category AS eventCategory,
                   evt.stage AS eventStage, evt.actor_role AS actorRole, evt.target_role AS targetRole,
                   evt.topic_id AS topicId, evt.claim_id AS claimId, evt.turn_id AS turnId,
                   evt.debate_round AS round, evt.progress, evt.payload_version AS payloadVersion,
                   evt.payload_json AS payloadJson, evt.occurred_at AS occurredAt,
                   report.report_id AS reportId, report.report_version AS reportVersion,
                    report.gate_version AS gateVersion, report.content_hash AS contentHash,
                   report.created_at AS reportCreatedAt
            FROM review_request root
            LEFT JOIN (
                SELECT review_id, MAX(event_sequence) AS latestSequence
                FROM review_event
                GROUP BY review_id
            ) latestEvent ON latestEvent.review_id = root.review_id
            LEFT JOIN review_event evt ON evt.review_id = latestEvent.review_id
                AND evt.event_sequence = latestEvent.latestSequence
            LEFT JOIN (
                SELECT reportInner.report_id, reportInner.review_id, reportInner.report_version,
                       reportInner.gate_version, reportInner.content_hash, reportInner.created_at
                FROM review_report reportInner
                INNER JOIN (
                    SELECT review_id, MAX(report_version) AS latestReportVersion
                    FROM review_report
                    GROUP BY review_id
                ) latestReport ON latestReport.review_id = reportInner.review_id
                    AND latestReport.latestReportVersion = reportInner.report_version
            ) report ON report.review_id = root.review_id
            WHERE (#{stage} IS NULL OR root.stage = #{stage})
              AND (#{hasReport} IS NULL
                   OR (#{hasReport} = TRUE AND report.report_id IS NOT NULL)
                   OR (#{hasReport} = FALSE AND report.report_id IS NULL))
              AND (#{activeOnly} IS NULL OR #{activeOnly} = FALSE
                   OR root.stage NOT IN ('COMPLETED', 'CANCELLED', 'FAILED'))
            ORDER BY COALESCE(evt.occurred_at, root.updated_at) DESC, root.review_id DESC,
                     COALESCE(evt.event_sequence, 0) DESC
            LIMIT #{offset}, #{limit}
            """)
    List<PlatformReviewRow> findReviewPage(
            @Param("stage") String stage,
            @Param("hasReport") Boolean hasReport,
            @Param("activeOnly") Boolean activeOnly,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM review_request root
            LEFT JOIN (
                SELECT review_id, MAX(report_version) AS latestReportVersion
                FROM review_report
                GROUP BY review_id
            ) latestReport ON latestReport.review_id = root.review_id
            WHERE (#{stage} IS NULL OR root.stage = #{stage})
              AND (#{hasReport} IS NULL
                   OR (#{hasReport} = TRUE AND latestReport.review_id IS NOT NULL)
                   OR (#{hasReport} = FALSE AND latestReport.review_id IS NULL))
              AND (#{activeOnly} IS NULL OR #{activeOnly} = FALSE
                   OR root.stage NOT IN ('COMPLETED', 'CANCELLED', 'FAILED'))
            """)
    long countReviewPage(
            @Param("stage") String stage,
            @Param("hasReport") Boolean hasReport,
            @Param("activeOnly") Boolean activeOnly);

    /**
     * @author zyj
     */
    record PlatformReviewRow(
            String reviewId,
            String stage,
            int attemptNo,
            long reviewVersion,
            LocalDateTime updatedAt,
            String eventId,
            Integer eventAttemptNo,
            Long sequence,
            String eventType,
            String eventCategory,
            String eventStage,
            String actorRole,
            String targetRole,
            String topicId,
            String claimId,
            String turnId,
            Integer round,
            Integer progress,
            Integer payloadVersion,
            String payloadJson,
            LocalDateTime occurredAt,
            String reportId,
            Long reportVersion,
            Long gateVersion,
            String contentHash,
            LocalDateTime reportCreatedAt) {
    }
}
