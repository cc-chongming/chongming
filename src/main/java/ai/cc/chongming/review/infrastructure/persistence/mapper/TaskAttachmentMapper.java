package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * [AIREVIEW-PLAN-031#0] MyBatis surface for the {@code dev_task_attachment} table. Lives in the
 * scanned persistence-mapper package (same as {@code DevTaskMapper}) so the MyBatis store binds
 * under {@code review.persistence.enabled=true}. List reads project {@code content} to NULL so
 * paging through metadata never ships blob bytes.
 *
 * @author wangli
 */
@Mapper
public interface TaskAttachmentMapper {

    @Insert("""
            INSERT INTO dev_task_attachment
                (attachment_id, task_id, file_name, content_type, file_size, uploaded_by, created_at, content)
            VALUES
                (#{attachmentId}, #{taskId}, #{fileName}, #{contentType}, #{fileSize}, #{uploadedBy},
                 #{createdAt}, #{content})
            """)
    int insert(TaskAttachmentRow row);

    @Select("""
            SELECT attachment_id AS attachmentId, task_id AS taskId, file_name AS fileName,
                   content_type AS contentType, file_size AS fileSize, uploaded_by AS uploadedBy,
                   created_at AS createdAt, NULL AS content
            FROM dev_task_attachment
            WHERE task_id = #{taskId}
            ORDER BY created_at, attachment_id
            """)
    List<TaskAttachmentRow> findByTask(@Param("taskId") String taskId);

    @Select("""
            SELECT attachment_id AS attachmentId, task_id AS taskId, file_name AS fileName,
                   content_type AS contentType, file_size AS fileSize, uploaded_by AS uploadedBy,
                   created_at AS createdAt, NULL AS content
            FROM dev_task_attachment
            WHERE task_id = #{taskId} AND attachment_id = #{attachmentId}
            """)
    TaskAttachmentRow find(@Param("taskId") String taskId, @Param("attachmentId") String attachmentId);

    @Select("""
            SELECT attachment_id AS attachmentId, task_id AS taskId, file_name AS fileName,
                   content_type AS contentType, file_size AS fileSize, uploaded_by AS uploadedBy,
                   created_at AS createdAt, content
            FROM dev_task_attachment
            WHERE task_id = #{taskId} AND attachment_id = #{attachmentId}
            """)
    TaskAttachmentRow findWithContent(@Param("taskId") String taskId, @Param("attachmentId") String attachmentId);

    @Delete("""
            DELETE FROM dev_task_attachment
            WHERE task_id = #{taskId} AND attachment_id = #{attachmentId}
            """)
    int delete(@Param("taskId") String taskId, @Param("attachmentId") String attachmentId);

    /**
     * @author wangli
     */
    record TaskAttachmentRow(
            String attachmentId,
            String taskId,
            String fileName,
            String contentType,
            long fileSize,
            String uploadedBy,
            LocalDateTime createdAt,
            byte[] content) {
    }
}
