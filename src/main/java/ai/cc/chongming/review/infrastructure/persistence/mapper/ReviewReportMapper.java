package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-M1] Persists and reloads immutable public report versions.
 *
 * @author zyj
 */
@Mapper
public interface ReviewReportMapper {

    @Insert("""
            INSERT INTO review_report
                (report_id, review_id, attempt_no, report_version, gate_version, report_status,
                 report_content, markdown_content, content_hash, published_at, version, created_at, updated_at)
            VALUES
                (#{row.reportId}, #{row.reviewId}, #{row.attemptNo}, #{row.reportVersion}, #{row.gateVersion},
                 'PUBLISHED', #{row.contentJson}, #{row.markdown}, #{row.contentHash}, #{row.createdAt},
                 #{row.reportVersion}, #{row.createdAt}, #{row.createdAt})
            """)
    int insert(@Param("row") ReportRow row);

    @Select("""
            SELECT report_id AS reportId, review_id AS reviewId, attempt_no AS attemptNo, report_version AS reportVersion,
                   gate_version AS gateVersion, content_hash AS contentHash, report_content AS contentJson,
                   markdown_content AS markdown, created_at AS createdAt
            FROM review_report
            WHERE review_id = #{reviewId}
            ORDER BY report_version DESC
            LIMIT 1
            """)
    ReportRow findLatest(@Param("reviewId") String reviewId);

    @Select("""
            SELECT report_id AS reportId, review_id AS reviewId, attempt_no AS attemptNo, report_version AS reportVersion,
                   gate_version AS gateVersion, content_hash AS contentHash, report_content AS contentJson,
                   markdown_content AS markdown, created_at AS createdAt
            FROM review_report
            WHERE review_id = #{reviewId} AND report_version = #{reportVersion}
            """)
    ReportRow findVersion(@Param("reviewId") String reviewId, @Param("reportVersion") long reportVersion);

    @Select("""
            SELECT report_id AS reportId, review_id AS reviewId, attempt_no AS attemptNo, report_version AS reportVersion,
                   gate_version AS gateVersion, content_hash AS contentHash, report_content AS contentJson,
                   markdown_content AS markdown, created_at AS createdAt
            FROM review_report
            WHERE review_id = #{reviewId}
            ORDER BY report_version
            """)
    List<ReportRow> findVersions(@Param("reviewId") String reviewId);

    @Select("""
            SELECT report.report_id AS reportId, report.review_id AS reviewId, report.attempt_no AS attemptNo,
                   report.report_version AS reportVersion,
                   report.gate_version AS gateVersion, report.content_hash AS contentHash,
                   report.report_content AS contentJson, report.markdown_content AS markdown,
                   report.created_at AS createdAt
            FROM review_report report
            INNER JOIN (
                SELECT review_id, MAX(report_version) AS latestReportVersion
                FROM review_report
                GROUP BY review_id
            ) latest ON latest.review_id = report.review_id AND latest.latestReportVersion = report.report_version
            ORDER BY report.created_at DESC, report.review_id DESC
            LIMIT #{limit}
            """)
    List<ReportRow> findLatestAcrossReviews(@Param("limit") int limit);

    @Select("""
            SELECT report.review_id AS reviewId, report.report_version AS reportVersion,
                   report.gate_version AS gateVersion, report.content_hash AS contentHash,
                   report.created_at AS createdAt
            FROM review_report report
            INNER JOIN (
                SELECT review_id, MAX(report_version) AS latestReportVersion
                FROM review_report
                GROUP BY review_id
            ) latest ON latest.review_id = report.review_id AND latest.latestReportVersion = report.report_version
            ORDER BY report.created_at DESC, report.review_id DESC
            LIMIT #{offset}, #{limit}
            """)
    List<ReportMetadataRow> findLatestMetadataPage(@Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM review_report report
            INNER JOIN (
                SELECT review_id, MAX(report_version) AS latestReportVersion
                FROM review_report
                GROUP BY review_id
            ) latest ON latest.review_id = report.review_id AND latest.latestReportVersion = report.report_version
            """)
    long countLatestMetadata();

    @Select("SELECT current_attempt_no FROM review_request WHERE review_id = #{reviewId}")
    Integer findCurrentAttempt(@Param("reviewId") String reviewId);

    /**
     * @author zyj
     */
    record ReportRow(
            String reportId,
            String reviewId,
            Integer attemptNo,
            long reportVersion,
            Long gateVersion,
            String contentHash,
            String contentJson,
            String markdown,
            LocalDateTime createdAt) {
    }

    /**
     * @author zyj
     */
    record ReportMetadataRow(
            String reviewId, long reportVersion, Long gateVersion, String contentHash, LocalDateTime createdAt) {
    }
}
