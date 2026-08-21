package ai.cc.chongming.task.domain.repository;

import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.TaskAttachmentId;
import ai.cc.chongming.task.domain.model.TaskAttachment;
import java.util.List;
import java.util.Optional;

/**
 * [AIREVIEW-PLAN-031#0] Storage contract for task delivery attachments. Implementations keep
 * metadata and byte content consistent; list reads never load content.
 *
 * @author wangli
 */
public interface TaskAttachmentStore {

    TaskAttachment save(TaskAttachment attachment, byte[] content);

    List<TaskAttachment> findByTask(DevTaskId taskId);

    Optional<TaskAttachment> find(DevTaskId taskId, TaskAttachmentId attachmentId);

    Optional<byte[]> findContent(DevTaskId taskId, TaskAttachmentId attachmentId);

    boolean delete(DevTaskId taskId, TaskAttachmentId attachmentId);
}
