package ai.cc.chongming.task.application;

import ai.cc.chongming.task.application.DevTaskQueryService.DevTaskView;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.DevTaskTypes.TaskAttachmentId;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.domain.model.TaskAttachment;
import ai.cc.chongming.task.domain.repository.TaskAttachmentStore;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-031#1] Delivery-attachment use cases for the task flow: the current holder (or
 * an administrator) uploads evidence while the task is actionable, every participant lists and
 * downloads it, and only the uploader or an administrator removes it.
 *
 * @author wangli
 */
@Service
public class DevTaskAttachmentService {

    /** Single-file ceiling keeps the LONGBLOB store within demo-scale database budgets. */
    public static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;

    private static final Set<DevTaskStatus> UPLOADABLE_STATUSES = Set.of(
            DevTaskStatus.DEVELOPING, DevTaskStatus.PAUSED, DevTaskStatus.PENDING_ACCEPTANCE);

    private final TaskAttachmentStore attachmentStore;
    private final DevTaskQueryService queryService;

    public DevTaskAttachmentService(TaskAttachmentStore attachmentStore, DevTaskQueryService queryService) {
        this.attachmentStore = Objects.requireNonNull(attachmentStore, "attachmentStore must not be null");
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
    }

    @Transactional
    public AttachmentView upload(
            DevTaskId taskId,
            String username,
            boolean administrator,
            String fileName,
            String contentType,
            byte[] content) {
        DevTaskView task = queryService.findById(taskId);
        requireUploader(task, username, administrator);
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("attachment content must not be empty");
        }
        if (content.length > MAX_ATTACHMENT_BYTES) {
            throw new TaskDomainException(
                    TaskErrorCode.ATTACHMENT_TOO_LARGE, "单个附件不能超过 20MB");
        }
        TaskAttachment attachment = new TaskAttachment(
                new TaskAttachmentId(UUID.randomUUID()),
                taskId,
                sanitizeFileName(fileName),
                contentType,
                content.length,
                username,
                null);
        return toView(attachmentStore.save(attachment, content));
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> list(DevTaskId taskId) {
        queryService.findById(taskId);
        return attachmentStore.findByTask(taskId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public AttachmentDownload download(DevTaskId taskId, TaskAttachmentId attachmentId) {
        TaskAttachment attachment = requireAttachment(taskId, attachmentId);
        byte[] content = attachmentStore.findContent(taskId, attachmentId)
                .orElseThrow(() -> new TaskDomainException(
                        TaskErrorCode.ATTACHMENT_NOT_FOUND, "attachment content was not found"));
        return new AttachmentDownload(attachment.fileName(), attachment.contentType(), content);
    }

    @Transactional
    public void delete(DevTaskId taskId, TaskAttachmentId attachmentId, String username, boolean administrator) {
        TaskAttachment attachment = requireAttachment(taskId, attachmentId);
        if (!administrator && !attachment.uploadedBy().equals(username)) {
            throw new TaskDomainException(TaskErrorCode.FORBIDDEN, "仅上传人或管理员可删除该附件");
        }
        attachmentStore.delete(taskId, attachmentId);
    }

    private TaskAttachment requireAttachment(DevTaskId taskId, TaskAttachmentId attachmentId) {
        return attachmentStore.find(taskId, attachmentId)
                .orElseThrow(() -> new TaskDomainException(
                        TaskErrorCode.ATTACHMENT_NOT_FOUND, "task attachment was not found"));
    }

    private void requireUploader(DevTaskView task, String username, boolean administrator) {
        if (administrator) {
            return;
        }
        String holder = task.currentHolderUsername() != null
                ? task.currentHolderUsername() : task.assigneeUsername();
        if (!username.equals(holder)) {
            throw new TaskDomainException(TaskErrorCode.FORBIDDEN, "仅当前持有人或管理员可上传任务附件");
        }
        if (!UPLOADABLE_STATUSES.contains(DevTaskStatus.valueOf(task.status()))) {
            throw new TaskDomainException(
                    TaskErrorCode.ILLEGAL_TASK_TRANSITION, "当前任务状态不允许上传附件");
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "attachment";
        }
        String name = fileName.trim();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0 && cut + 1 < name.length()) {
            name = name.substring(cut + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        return name.isBlank() ? "attachment" : name;
    }

    private AttachmentView toView(TaskAttachment attachment) {
        return new AttachmentView(
                attachment.attachmentId().value().toString(),
                attachment.taskId().value().toString(),
                attachment.fileName(),
                attachment.contentType(),
                attachment.fileSize(),
                attachment.uploadedBy(),
                attachment.createdAt().toString());
    }

    /**
     * @author wangli
     */
    public record AttachmentView(
            String attachmentId,
            String taskId,
            String fileName,
            String contentType,
            long fileSize,
            String uploadedBy,
            String createdAt) {
    }

    /**
     * @author wangli
     */
    public record AttachmentDownload(String fileName, String contentType, byte[] content) {
    }
}
