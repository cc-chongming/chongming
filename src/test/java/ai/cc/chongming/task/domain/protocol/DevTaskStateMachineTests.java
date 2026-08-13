package ai.cc.chongming.task.domain.protocol;

import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Walks the full transition matrix of the development-task state machine, asserting every
 * allowed edge and every rejected edge individually.
 *
 * @author wangli
 */
class DevTaskStateMachineTests {

    private final DevTaskStateMachine stateMachine = new DevTaskStateMachine();

    @Test
    void allowsDispatchFromPendingAssignToDeveloping() {
        assertThat(stateMachine.transition(DevTaskStatus.PENDING_ASSIGN, DevTaskStatus.DEVELOPING))
                .isEqualTo(DevTaskStatus.DEVELOPING);
    }

    @Test
    void allowsSubmittingDevelopingTaskForAcceptance() {
        assertThat(stateMachine.transition(DevTaskStatus.DEVELOPING, DevTaskStatus.PENDING_ACCEPTANCE))
                .isEqualTo(DevTaskStatus.PENDING_ACCEPTANCE);
    }

    @Test
    void allowsAcceptingPendingAcceptanceTaskToDone() {
        assertThat(stateMachine.transition(DevTaskStatus.PENDING_ACCEPTANCE, DevTaskStatus.DONE))
                .isEqualTo(DevTaskStatus.DONE);
    }

    @Test
    void allowsRejectingPendingAcceptanceTaskBackToDeveloping() {
        assertThat(stateMachine.transition(DevTaskStatus.PENDING_ACCEPTANCE, DevTaskStatus.DEVELOPING))
                .isEqualTo(DevTaskStatus.DEVELOPING);
    }

    @Test
    void rejectsEveryTransitionOutsideTheAllowedMatrix() {
        Set<DevTaskStatus> allowed = EnumSet.noneOf(DevTaskStatus.class);
        for (DevTaskStatus from : DevTaskStatus.values()) {
            for (DevTaskStatus to : DevTaskStatus.values()) {
                boolean expected =
                        (from == DevTaskStatus.PENDING_ASSIGN && to == DevTaskStatus.DEVELOPING)
                                || (from == DevTaskStatus.DEVELOPING && to == DevTaskStatus.PENDING_ACCEPTANCE)
                                || (from == DevTaskStatus.PENDING_ACCEPTANCE && to == DevTaskStatus.DONE)
                                || (from == DevTaskStatus.PENDING_ACCEPTANCE && to == DevTaskStatus.DEVELOPING);
                assertThat(stateMachine.canTransition(from, to))
                        .as("transition %s -> %s", from, to)
                        .isEqualTo(expected);
                if (expected) {
                    allowed.add(to);
                }
            }
        }
        assertThat(allowed).containsExactlyInAnyOrder(
                DevTaskStatus.DEVELOPING, DevTaskStatus.PENDING_ACCEPTANCE, DevTaskStatus.DONE);
    }

    @Test
    void rejectedTransitionCarriesIllegalTaskTransitionCode() {
        assertThatThrownBy(() -> stateMachine.transition(DevTaskStatus.DONE, DevTaskStatus.DEVELOPING))
                .isInstanceOf(TaskDomainException.class)
                .satisfies(exception -> assertThat(((TaskDomainException) exception).errorCode())
                        .isEqualTo(TaskErrorCode.ILLEGAL_TASK_TRANSITION));
    }
}
