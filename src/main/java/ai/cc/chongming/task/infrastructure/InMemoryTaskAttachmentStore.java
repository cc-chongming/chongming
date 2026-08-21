package ai.cc.chongming.task.infrastructure;

import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.TaskAttachmentId;
import ai.cc.chongming.task.domain.model.TaskAttachment;
import ai.cc.chongming.task.domain.repository.TaskAttachmentStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * [AIREVIEW-PLAN-031#0] In-memory attachment store used when MySQL persistence is disabled;
 * keeps tests and the zero-config profile behaviorally identical to the MyBatis store.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryTaskAttachmentStore implements TaskAttachmentStore {

    private record Stored(TaskAttachment attachment, byte[] content) {
    }

    private final Map<DevTaskId, Map<TaskAttachmentId, Stored>> attachmentsByTask = new ConcurrentHashMap<>();

    @Override
    public TaskAttachment save(TaskAttachment attachment, byte[] content) {
        Objects.requireNonNull(attachment, "attachment must not be null");
        Objects.requireNonNull(content, "content must not be null");
        attachmentsByTask
                .computeIfAbsent(attachment.taskId(), ignored -> new ConcurrentHashMap<>())
                .put(attachment.attachmentId(), new Stored(attachment, content.clone()));
        return attachment;
    }

    @Override
    public List<TaskAttachment> findByTask(DevTaskId taskId) {
        Map<TaskAttachmentId, Stored> stored = attachmentsByTask.get(taskId);
        if (stored == null) {
            return List.of();
        }
        return stored.values().stream()
                .map(Stored::attachment)
                .sorted(Comparator.comparing(TaskAttachment::createdAt).thenComparing(
                        attachment -> attachment.attachmentId().value().toString()))
                .toList();
    }

    @Override
    public Optional<TaskAttachment> find(DevTaskId taskId, TaskAttachmentId attachmentId) {
        Map<TaskAttachmentId, Stored> stored = attachmentsByTask.get(taskId);
        return stored == null ? Optional.empty()
                : Optional.ofNullable(stored.get(attachmentId)).map(Stored::attachment);
    }

    @Override
    public Optional<byte[]> findContent(DevTaskId taskId, TaskAttachmentId attachmentId) {
        Map<TaskAttachmentId, Stored> stored = attachmentsByTask.get(taskId);
        return stored == null ? Optional.empty()
                : Optional.ofNullable(stored.get(attachmentId)).map(entry -> entry.content().clone());
    }

    @Override
    public boolean delete(DevTaskId taskId, TaskAttachmentId attachmentId) {
        Map<TaskAttachmentId, Stored> stored = attachmentsByTask.get(taskId);
        return stored != null && stored.remove(attachmentId) != null;
    }
}
