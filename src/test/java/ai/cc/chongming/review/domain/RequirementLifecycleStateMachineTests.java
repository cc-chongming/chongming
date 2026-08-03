package ai.cc.chongming.review.domain;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.protocol.RequirementLifecycleStateMachine;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-021#1] Verifies the requirement lifecycle independently of review orchestration.
 *
 * @author zyj
 */
class RequirementLifecycleStateMachineTests {

    private final RequirementLifecycleStateMachine stateMachine = new RequirementLifecycleStateMachine();

    @Test
    void permitsTheFullApprovedDeliveryPath() {
        List<RequirementStatus> path = List.of(
                RequirementStatus.DRAFT,
                RequirementStatus.PENDING_REVIEW,
                RequirementStatus.REVIEWING,
                RequirementStatus.APPROVED,
                RequirementStatus.DEVELOPING,
                RequirementStatus.DONE);

        RequirementStatus current = path.getFirst();
        for (RequirementStatus next : path.subList(1, path.size())) {
            current = stateMachine.transition(current, next);
        }

        assertThat(current).isEqualTo(RequirementStatus.DONE);
    }

    @Test
    void permitsReturnedRequirementToBeResubmitted() {
        assertThat(stateMachine.transition(RequirementStatus.REVIEWING, RequirementStatus.RETURNED))
                .isEqualTo(RequirementStatus.RETURNED);
        assertThat(stateMachine.transition(RequirementStatus.RETURNED, RequirementStatus.PENDING_REVIEW))
                .isEqualTo(RequirementStatus.PENDING_REVIEW);
    }

    @Test
    void permitsDraftToBeCancelled() {
        assertThat(stateMachine.transition(RequirementStatus.DRAFT, RequirementStatus.CANCELLED))
                .isEqualTo(RequirementStatus.CANCELLED);
    }

    @Test
    void rejectsSkippingTheReviewLifecycle() {
        assertThatThrownBy(() -> stateMachine.transition(RequirementStatus.DRAFT, RequirementStatus.APPROVED))
                .isInstanceOf(RequirementDomainException.class)
                .extracting(error -> ((RequirementDomainException) error).errorCode())
                .isEqualTo(RequirementErrorCode.ILLEGAL_LIFECYCLE_TRANSITION);
    }
}
