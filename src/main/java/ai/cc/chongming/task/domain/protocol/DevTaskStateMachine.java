package ai.cc.chongming.task.domain.protocol;

import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Table-driven state machine for the development-task lifecycle, including the
 * acceptance-rejection path that sends a task back to development.
 *
 * @author wangli
 */
public final class DevTaskStateMachine {

    private final Map<DevTaskStatus, Set<DevTaskStatus>> transitions = new EnumMap<>(DevTaskStatus.class);

    public DevTaskStateMachine() {
        allow(DevTaskStatus.PENDING_ASSIGN, DevTaskStatus.DEVELOPING);
        allow(DevTaskStatus.DEVELOPING, DevTaskStatus.PENDING_ACCEPTANCE);
        allow(DevTaskStatus.PENDING_ACCEPTANCE, DevTaskStatus.DONE, DevTaskStatus.DEVELOPING);
    }

    public DevTaskStatus transition(DevTaskStatus current, DevTaskStatus next) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(next, "next must not be null");
        if (!canTransition(current, next)) {
            throw new TaskDomainException(
                    TaskErrorCode.ILLEGAL_TASK_TRANSITION,
                    "cannot transition dev task from " + current + " to " + next);
        }
        return next;
    }

    public boolean canTransition(DevTaskStatus current, DevTaskStatus next) {
        return transitions.getOrDefault(current, Set.of()).contains(next);
    }

    private void allow(DevTaskStatus from, DevTaskStatus... nextStatuses) {
        transitions.put(from, EnumSet.copyOf(List.of(nextStatuses)));
    }
}
