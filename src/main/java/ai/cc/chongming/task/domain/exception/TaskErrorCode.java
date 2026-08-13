package ai.cc.chongming.task.domain.exception;

/**
 * Stable error codes for development-task dispatch and acceptance commands.
 *
 * @author wangli
 */
public enum TaskErrorCode {
    TASK_NOT_FOUND,
    ILLEGAL_TASK_TRANSITION,
    VERSION_CONFLICT,
    FORBIDDEN,
    TASK_REQUIREMENT_STATE_CONFLICT
}
