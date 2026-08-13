package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis statements for the development-task aggregate. Lives in this package on purpose so
 * the existing {@code @MapperScan} in ReviewPersistenceConfiguration picks it up without any
 * change to that wiring. Page reads LEFT JOIN the requirement table so the joined requirement
 * title travels with every row in one statement instead of triggering per-row lookups.
 *
 * @author wangli
 */
public interface DevTaskMapper {

    @Insert("""
            INSERT INTO dev_task
                (task_id, requirement_id, review_id, title, task_status, assignee_username,
                 dispatcher_username, acceptance_note, version, created_at, updated_at)
            VALUES
                (#{taskId}, #{requirementId}, #{reviewId}, #{title}, #{status}, #{assigneeUsername},
                 #{dispatcherUsername}, #{acceptanceNote}, #{version}, #{createdAt}, #{updatedAt})
            """)
    int insert(DevTaskRow row);

    @Update("""
            UPDATE dev_task
            SET task_status = #{row.status}, assignee_username = #{row.assigneeUsername},
                dispatcher_username = #{row.dispatcherUsername}, acceptance_note = #{row.acceptanceNote},
                version = #{row.version}, updated_at = #{row.updatedAt}
            WHERE task_id = #{row.taskId} AND version = #{expectedVersion}
            """)
    int update(@Param("row") DevTaskRow row, @Param("expectedVersion") long expectedVersion);

    @Select("""
            SELECT t.task_id AS taskId, t.requirement_id AS requirementId, t.review_id AS reviewId,
                   t.title, t.task_status AS status, t.assignee_username AS assigneeUsername,
                   t.dispatcher_username AS dispatcherUsername, t.acceptance_note AS acceptanceNote,
                   t.version, t.created_at AS createdAt, t.updated_at AS updatedAt,
                   r.title AS requirementTitle, u.display_name AS assigneeDisplayName
            FROM dev_task t
            LEFT JOIN requirement r ON r.requirement_id = t.requirement_id
            LEFT JOIN users u ON u.username = t.assignee_username
            WHERE t.task_id = #{taskId}
            """)
    DevTaskRow findById(@Param("taskId") String taskId);

    @Select("""
            SELECT task_id AS taskId, requirement_id AS requirementId, review_id AS reviewId,
                   title, task_status AS status, assignee_username AS assigneeUsername,
                   dispatcher_username AS dispatcherUsername, acceptance_note AS acceptanceNote,
                   version, created_at AS createdAt, updated_at AS updatedAt,
                   NULL AS requirementTitle, NULL AS assigneeDisplayName
            FROM dev_task WHERE requirement_id = #{requirementId}
            """)
    DevTaskRow findByRequirementId(@Param("requirementId") String requirementId);

    @Select("""
            <script>
            SELECT t.task_id AS taskId, t.requirement_id AS requirementId, t.review_id AS reviewId,
                   t.title, t.task_status AS status, t.assignee_username AS assigneeUsername,
                   t.dispatcher_username AS dispatcherUsername, t.acceptance_note AS acceptanceNote,
                   t.version, t.created_at AS createdAt, t.updated_at AS updatedAt,
                   r.title AS requirementTitle, u.display_name AS assigneeDisplayName
            FROM dev_task t
            LEFT JOIN requirement r ON r.requirement_id = t.requirement_id
            LEFT JOIN users u ON u.username = t.assignee_username
            <where>
              <if test="status != null"> t.task_status = #{status} </if>
              <if test="assigneeUsername != null"> AND t.assignee_username = #{assigneeUsername} </if>
              <if test="requirementId != null"> AND t.requirement_id = #{requirementId} </if>
              <if test="keyword != null"> AND (t.title LIKE CONCAT('%', #{keyword}, '%') OR r.title LIKE CONCAT('%', #{keyword}, '%')) </if>
            </where>
            ORDER BY t.updated_at DESC, t.task_id ASC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<DevTaskRow> findPage(
            @Param("status") String status,
            @Param("assigneeUsername") String assigneeUsername,
            @Param("keyword") String keyword,
            @Param("requirementId") String requirementId,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM dev_task t
            LEFT JOIN requirement r ON r.requirement_id = t.requirement_id
            <where>
              <if test="status != null"> t.task_status = #{status} </if>
              <if test="assigneeUsername != null"> AND t.assignee_username = #{assigneeUsername} </if>
              <if test="requirementId != null"> AND t.requirement_id = #{requirementId} </if>
              <if test="keyword != null"> AND (t.title LIKE CONCAT('%', #{keyword}, '%') OR r.title LIKE CONCAT('%', #{keyword}, '%')) </if>
            </where>
            </script>
            """)
    long countPage(
            @Param("status") String status,
            @Param("assigneeUsername") String assigneeUsername,
            @Param("keyword") String keyword,
            @Param("requirementId") String requirementId);

    @Select("SELECT task_status AS status, COUNT(*) AS total FROM dev_task GROUP BY task_status")
    List<StatusCountRow> countByStatus();

    /**
     * Flat row shape for the {@code dev_task} table; {@code requirementTitle} and
     * {@code assigneeDisplayName} are only populated by statements that join the
     * requirement/users tables.
     *
     * @author wangli
     */
    record DevTaskRow(
            String taskId,
            String requirementId,
            String reviewId,
            String title,
            String status,
            String assigneeUsername,
            String dispatcherUsername,
            String acceptanceNote,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String requirementTitle,
            String assigneeDisplayName) {
    }

    /**
     * @author wangli
     */
    record StatusCountRow(String status, long total) {
    }
}
