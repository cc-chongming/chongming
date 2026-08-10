package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

/**
 * [AIREVIEW-PLAN-021#2] MyBatis statements for the requirement aggregate.
 *
 * @author zyj
 */
public interface RequirementMapper {

    @Insert("""
            INSERT INTO requirement
                (requirement_id, title, description_md, requirement_status, creator_id, assignee_id,
                 repository_path, priority, review_id, version, created_at, updated_at)
            VALUES
                (#{id}, #{title}, #{description}, #{status}, #{creatorId}, #{assigneeId},
                 #{repositoryPath}, #{priority}, #{reviewId}, #{version}, #{createdAt}, #{updatedAt})
            """)
    int insert(RequirementRow row);

    @Update("""
            UPDATE requirement
            SET title = #{row.title}, description_md = #{row.description}, requirement_status = #{row.status},
                assignee_id = #{row.assigneeId}, repository_path = #{row.repositoryPath}, priority = #{row.priority},
                review_id = #{row.reviewId}, version = #{row.version}, updated_at = #{row.updatedAt}
            WHERE requirement_id = #{row.id} AND version = #{expectedVersion}
            """)
    int update(@Param("row") RequirementRow row, @Param("expectedVersion") long expectedVersion);

    @Delete("DELETE FROM requirement WHERE requirement_id = #{requirementId} AND version = #{expectedVersion}")
    int delete(@Param("requirementId") String requirementId, @Param("expectedVersion") long expectedVersion);

    @Select("""
            SELECT requirement_id AS id, title, description_md AS description, requirement_status AS status,
                   creator_id AS creatorId, assignee_id AS assigneeId, repository_path AS repositoryPath,
                   priority, review_id AS reviewId, version, created_at AS createdAt, updated_at AS updatedAt
            FROM requirement WHERE requirement_id = #{requirementId}
            """)
    RequirementRow findById(@Param("requirementId") String requirementId);

    @Select("""
            SELECT requirement_id AS id, title, description_md AS description, requirement_status AS status,
                   creator_id AS creatorId, assignee_id AS assigneeId, repository_path AS repositoryPath,
                   priority, review_id AS reviewId, version, created_at AS createdAt, updated_at AS updatedAt
            FROM requirement WHERE review_id = #{reviewId}
            ORDER BY updated_at DESC LIMIT 1
            """)
    RequirementRow findByReviewId(@Param("reviewId") String reviewId);

    @Select("""
            <script>
            SELECT requirement_id AS id, title, description_md AS description, requirement_status AS status,
                   creator_id AS creatorId, assignee_id AS assigneeId, repository_path AS repositoryPath,
                   priority, review_id AS reviewId, version, created_at AS createdAt, updated_at AS updatedAt
            FROM requirement
            <where>
              <if test="status != null"> requirement_status = #{status} </if>
              <if test="assigneeId != null"> AND assignee_id = #{assigneeId} </if>
              <if test="keyword != null"> AND (title LIKE CONCAT('%', #{keyword}, '%') OR description_md LIKE CONCAT('%', #{keyword}, '%')) </if>
            </where>
            ORDER BY updated_at DESC, requirement_id ASC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<RequirementRow> findPage(
            @Param("status") String status,
            @Param("assigneeId") String assigneeId,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*) FROM requirement
            <where>
              <if test="status != null"> requirement_status = #{status} </if>
              <if test="assigneeId != null"> AND assignee_id = #{assigneeId} </if>
              <if test="keyword != null"> AND (title LIKE CONCAT('%', #{keyword}, '%') OR description_md LIKE CONCAT('%', #{keyword}, '%')) </if>
            </where>
            </script>
            """)
    long countPage(@Param("status") String status, @Param("assigneeId") String assigneeId, @Param("keyword") String keyword);

    @Select("SELECT requirement_status AS status, COUNT(*) AS total FROM requirement GROUP BY requirement_status")
    List<StatusCountRow> countByStatus();

    /**
     * [AIREVIEW-PLAN-023#3] Atomically creates the requirement-scoped launch reservation.
     */
    @Insert("""
            INSERT IGNORE INTO requirement_review_launch_command
                (requirement_id, idempotency_key, request_fingerprint, owner_token, lease_until)
            VALUES
                (#{requirementId}, #{idempotencyKey}, #{requestFingerprint}, #{ownerToken}, #{leaseUntil})
            """)
    int insertLaunchCommand(RequirementReviewLaunchCommandRow row);

    @Select("""
            SELECT requirement_id AS requirementId, idempotency_key AS idempotencyKey,
                   request_fingerprint AS requestFingerprint, owner_token AS ownerToken,
                   lease_until AS leaseUntil, review_id AS reviewId
            FROM requirement_review_launch_command
            WHERE requirement_id = #{requirementId} AND idempotency_key = #{idempotencyKey}
            """)
    RequirementReviewLaunchCommandRow findLaunchCommand(
            @Param("requirementId") String requirementId, @Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE requirement_review_launch_command
            SET owner_token = #{ownerToken}, lease_until = #{leaseUntil}
            WHERE requirement_id = #{requirementId} AND idempotency_key = #{idempotencyKey}
              AND request_fingerprint = #{requestFingerprint} AND review_id IS NULL
              AND lease_until <= #{now}
            """)
    int takeOverExpiredLaunchCommand(
            @Param("requirementId") String requirementId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("ownerToken") String ownerToken,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE requirement_review_launch_command
            SET lease_until = #{leaseUntil}
            WHERE requirement_id = #{requirementId} AND idempotency_key = #{idempotencyKey}
              AND owner_token = #{ownerToken} AND review_id IS NULL
            """)
    int renewLaunchCommand(
            @Param("requirementId") String requirementId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("ownerToken") String ownerToken,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE requirement_review_launch_command
            SET review_id = #{reviewId}, lease_until = NULL
            WHERE requirement_id = #{requirementId} AND idempotency_key = #{idempotencyKey}
              AND request_fingerprint = #{requestFingerprint} AND owner_token = #{ownerToken}
              AND review_id IS NULL
            """)
    int completeLaunchCommand(
            @Param("requirementId") String requirementId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("ownerToken") String ownerToken,
            @Param("reviewId") String reviewId);

    @Delete("""
            DELETE FROM requirement_review_launch_command
            WHERE requirement_id = #{requirementId} AND idempotency_key = #{idempotencyKey}
              AND owner_token = #{ownerToken} AND review_id IS NULL
            """)
    int releaseLaunchCommand(
            @Param("requirementId") String requirementId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("ownerToken") String ownerToken);

    /**
     * @author zyj
     */
    record RequirementRow(
            String id,
            String title,
            String description,
            String status,
            String creatorId,
            String assigneeId,
            String repositoryPath,
            String priority,
            String reviewId,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * @author zyj
     */
    record StatusCountRow(String status, long total) {
    }

    /**
     * [AIREVIEW-PLAN-023#3] Durable launch reservation row.
     *
     * @author zyj
     */
    record RequirementReviewLaunchCommandRow(
            String requirementId,
            String idempotencyKey,
            String requestFingerprint,
            String ownerToken,
            LocalDateTime leaseUntil,
            String reviewId) {
    }
}
