package ai.cc.chongming.task.domain.model;

import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.TaskAttachmentId;
import java.time.Instant;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-031#0] Immutable metadata of one delivery attachment bound to a dev task.
 * The byte content lives beside the metadata in the store and is never carried by this record.
 *
 * @author wangli
 */
public record TaskAttachment(
        TaskAttachmentId attachmentId,
        DevTaskId taskId,
        String fileName,
        String contentType,
        long fileSize,
        String uploadedBy,
        Instant createdAt) {

    public TaskAttachment {
        Objects.requireNonNull(attachmentId, "attachmentId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        fileName = fileName.trim();
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must not be negative");
        }
        if (uploadedBy == null || uploadedBy.isBlank()) {
            throw new IllegalArgumentException("uploadedBy must not be blank");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
