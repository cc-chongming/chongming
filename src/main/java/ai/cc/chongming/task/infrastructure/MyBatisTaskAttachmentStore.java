package ai.cc.chongming.task.infrastructure;

import ai.cc.chongming.review.infrastructure.persistence.mapper.TaskAttachmentMapper;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.TaskAttachmentId;
import ai.cc.chongming.task.domain.model.TaskAttachment;
import ai.cc.chongming.task.domain.repository.TaskAttachmentStore;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-031#0] MySQL-backed attachment store. {@code created_at} follows the project's
 * UTC wall-clock column convention so the read side restores the exact instant.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisTaskAttachmentStore implements TaskAttachmentStore {

    private final TaskAttachmentMapper mapper;

    public MyBatisTaskAttachmentStore(TaskAttachmentMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public TaskAttachment save(TaskAttachment attachment, byte[] content) {
        Objects.requireNonNull(attachment, "attachment must not be null");
        Objects.requireNonNull(content, "content must not be null");
        mapper.insert(new TaskAttachmentMapper.TaskAttachmentRow(
                attachment.attachmentId().value().toString(),
                attachment.taskId().value().toString(),
                attachment.fileName(),
                attachment.contentType(),
                attachment.fileSize(),
                attachment.uploadedBy(),
                LocalDateTime.ofInstant(attachment.createdAt(), ZoneOffset.UTC),
                content));
        return attachment;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskAttachment> findByTask(DevTaskId taskId) {
        return mapper.findByTask(taskId.value().toString()).stream().map(this::toAttachment).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskAttachment> find(DevTaskId taskId, TaskAttachmentId attachmentId) {
        TaskAttachmentMapper.TaskAttachmentRow row =
                mapper.find(taskId.value().toString(), attachmentId.value().toString());
        return row == null ? Optional.empty() : Optional.of(toAttachment(row));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> findContent(DevTaskId taskId, TaskAttachmentId attachmentId) {
        // Returning the row record (not a bare byte[]) keeps MyBatis on the BLOB column's
        // ByteArrayTypeHandler; a scalar byte[] return is mis-routed to ByteTypeHandler and
        // fails on LONGBLOB with NumberOutOfRange.
        return Optional.ofNullable(mapper.findWithContent(taskId.value().toString(), attachmentId.value().toString()))
                .map(TaskAttachmentMapper.TaskAttachmentRow::content);
    }

    @Override
    @Transactional
    public boolean delete(DevTaskId taskId, TaskAttachmentId attachmentId) {
        return mapper.delete(taskId.value().toString(), attachmentId.value().toString()) == 1;
    }

    private TaskAttachment toAttachment(TaskAttachmentMapper.TaskAttachmentRow row) {
        return new TaskAttachment(
                new TaskAttachmentId(UUID.fromString(row.attachmentId())),
                new DevTaskId(UUID.fromString(row.taskId())),
                row.fileName(),
                row.contentType(),
                row.fileSize(),
                row.uploadedBy(),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
