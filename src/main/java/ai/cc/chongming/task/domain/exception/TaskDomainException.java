package ai.cc.chongming.task.domain.exception;

import java.util.Objects;

/**
 * Carries a stable task-domain error code across the dispatch and acceptance boundary.
 *
 * @author wangli
 */
public final class TaskDomainException extends RuntimeException {

    private final TaskErrorCode errorCode;

    public TaskDomainException(TaskErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public TaskErrorCode errorCode() {
        return errorCode;
    }
}
