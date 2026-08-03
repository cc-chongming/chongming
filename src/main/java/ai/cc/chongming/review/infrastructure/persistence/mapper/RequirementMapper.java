package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}
