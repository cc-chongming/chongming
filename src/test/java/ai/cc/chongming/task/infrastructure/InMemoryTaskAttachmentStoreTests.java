package ai.cc.chongming.task.infrastructure;

import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.TaskAttachmentId;
import ai.cc.chongming.task.domain.model.TaskAttachment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-031#3] Behavior contract of the in-memory attachment store shared with the
 * MyBatis implementation: metadata ordering, content isolation and delete semantics.
 *
 * @author wangli
 */
class InMemoryTaskAttachmentStoreTests {

    private final InMemoryTaskAttachmentStore store = new InMemoryTaskAttachmentStore();

    @Test
    void saveAndFindByTaskReturnMetadataOrderedByCreationTime() {
        DevTaskId taskId = new DevTaskId(UUID.randomUUID());
        TaskAttachment first = attachment(taskId, "design.md", Instant.parse("2026-08-20T10:00:00Z"), 1L);
        TaskAttachment second = attachment(taskId, "patch.diff", Instant.parse("2026-08-20T11:00:00Z"), 2L);
        store.save(first, "a".getBytes(StandardCharsets.UTF_8));
        store.save(second, "bb".getBytes(StandardCharsets.UTF_8));

        var listed = store.findByTask(taskId);
        assertThat(listed).hasSize(2);
        assertThat(listed).extracting(TaskAttachment::fileName).containsExactly("design.md", "patch.diff");
        // List reads never expose blob content.
        assertThat(listed).extracting(TaskAttachment::fileSize).containsExactly(1L, 2L);
    }

    @Test
    void findContentReturnsAnIsolatedCopy() {
        DevTaskId taskId = new DevTaskId(UUID.randomUUID());
        TaskAttachment attachment = attachment(taskId, "notes.txt", null, 5L);
        byte[] uploaded = "hello".getBytes(StandardCharsets.UTF_8);
        store.save(attachment, uploaded);

        byte[] downloaded = store.findContent(taskId, attachment.attachmentId()).orElseThrow();
        assertThat(downloaded).isEqualTo(uploaded);
        downloaded[0] = 'X';
        assertThat(store.findContent(taskId, attachment.attachmentId()).orElseThrow())
                .isEqualTo(uploaded);
    }

    @Test
    void deleteRemovesOnlyTheTargetedAttachment() {
        DevTaskId taskId = new DevTaskId(UUID.randomUUID());
        TaskAttachment kept = attachment(taskId, "kept.md", null, 1L);
        TaskAttachment removed = attachment(taskId, "removed.md", null, 1L);
        store.save(kept, "k".getBytes(StandardCharsets.UTF_8));
        store.save(removed, "r".getBytes(StandardCharsets.UTF_8));

        assertThat(store.delete(taskId, removed.attachmentId())).isTrue();
        assertThat(store.delete(taskId, removed.attachmentId())).isFalse();
        assertThat(store.find(taskId, kept.attachmentId())).isPresent();
        assertThat(store.find(taskId, removed.attachmentId())).isEmpty();
    }

    private TaskAttachment attachment(DevTaskId taskId, String fileName, Instant createdAt, long fileSize) {
        return new TaskAttachment(
                new TaskAttachmentId(UUID.randomUUID()), taskId, fileName, "text/plain",
                fileSize, "bob", createdAt);
    }
}
