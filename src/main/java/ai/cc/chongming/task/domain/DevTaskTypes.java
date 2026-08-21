package ai.cc.chongming.task.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Defines value types for the development-task lifecycle aggregate.
 *
 * @author wangli
 */
public final class DevTaskTypes {

    private DevTaskTypes() {
    }

    /**
     * @author wangli
     */
    public record DevTaskId(UUID value) {
        public DevTaskId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * [AIREVIEW-PLAN-031#0] Identity of one delivery attachment bound to a dev task.
     *
     * @author wangli
     */
    public record TaskAttachmentId(UUID value) {
        public TaskAttachmentId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * @author wangli
     */
    public enum DevTaskStatus {
        PENDING_ASSIGN,
        DEVELOPING,
        PAUSED,
        PENDING_ACCEPTANCE,
        DONE,
        CANCELLED;

        public boolean isTerminal() {
            return this == DONE || this == CANCELLED;
        }
    }

    /**
     * [AIREVIEW-PLAN-030] Immutable handoff entry appended on every holder change; the history
     * is append-only and feeds the task detail timeline plus visibility expansion.
     *
     * @author wangli
     */
    public record HandoffEntry(int seq, String fromUsername, String toUsername, String note, Instant at) {
        public HandoffEntry {
            if (seq < 1) {
                throw new IllegalArgumentException("seq must be positive");
            }
            fromUsername = Objects.requireNonNull(fromUsername, "fromUsername must not be null");
            toUsername = Objects.requireNonNull(toUsername, "toUsername must not be null");
            at = at == null ? Instant.now() : at;
        }
    }
}
