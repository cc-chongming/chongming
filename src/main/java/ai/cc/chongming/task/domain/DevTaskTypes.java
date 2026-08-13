package ai.cc.chongming.task.domain;

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
     * @author wangli
     */
    public enum DevTaskStatus {
        PENDING_ASSIGN,
        DEVELOPING,
        PENDING_ACCEPTANCE,
        DONE;

        public boolean isTerminal() {
            return this == DONE;
        }
    }
}
