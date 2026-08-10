package ai.cc.chongming.review.domain;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-003#1.1,#1.3] Verifies the fixed review lifecycle independently of infrastructure.
 *
 * @author wangli
 */
class ReviewStateMachineTests {

    private final ReviewStateMachine stateMachine = new ReviewStateMachine();

    @Test
    void permitsTheFixedHappyPath() {
        List<ReviewStage> stages = List.of(
                ReviewStage.PENDING,
                ReviewStage.SNAPSHOTTING,
                ReviewStage.PLANNING,
                ReviewStage.INITIAL_REVIEW,
                ReviewStage.CONFLICT_DETECTION,
                ReviewStage.DEBATE_ROUND_1,
                ReviewStage.DEBATE_ROUND_2,
                ReviewStage.JUDGING,
                ReviewStage.WAITING_HUMAN,
                ReviewStage.NOTIFYING,
                ReviewStage.COMPLETED);

        ReviewStage current = stages.getFirst();
        for (ReviewStage next : stages.subList(1, stages.size())) {
            current = stateMachine.transition(current, next);
        }

        assertThat(current).isEqualTo(ReviewStage.COMPLETED);
    }

    @ParameterizedTest
    @MethodSource("illegalTransitions")
    void rejectsIllegalTransitions(ReviewStage current, ReviewStage next) {
        assertThatThrownBy(() -> stateMachine.transition(current, next))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(error -> ((ReviewDomainException) error).errorCode())
                .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void runningStagesCanFailButTerminalStagesCannot() {
        assertThat(stateMachine.transition(ReviewStage.INITIAL_REVIEW, ReviewStage.FAILED))
                .isEqualTo(ReviewStage.FAILED);

        assertThatThrownBy(() -> stateMachine.transition(ReviewStage.COMPLETED, ReviewStage.FAILED))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(error -> ((ReviewDomainException) error).errorCode())
                .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void cancellationAlwaysPassesThroughCancelling() {
        assertThat(stateMachine.transition(ReviewStage.PLANNING, ReviewStage.CANCELLING))
                .isEqualTo(ReviewStage.CANCELLING);
        assertThat(stateMachine.transition(ReviewStage.CANCELLING, ReviewStage.CANCELLED))
                .isEqualTo(ReviewStage.CANCELLED);

        assertThatThrownBy(() -> stateMachine.transition(ReviewStage.PLANNING, ReviewStage.CANCELLED))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(error -> ((ReviewDomainException) error).errorCode())
                .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void permitsEarlyConvergenceFromRoundOneToJudging() {
        // [AIREVIEW-PLAN-024#方案4] When no valid open action survives round one the debate may
        // converge straight to judging instead of running an empty second round.
        assertThat(stateMachine.transition(ReviewStage.DEBATE_ROUND_1, ReviewStage.JUDGING))
                .isEqualTo(ReviewStage.JUDGING);
    }

    private static Stream<Arguments> illegalTransitions() {
        return Stream.of(
                Arguments.of(ReviewStage.PENDING, ReviewStage.PLANNING),
                Arguments.of(ReviewStage.INITIAL_REVIEW, ReviewStage.DEBATE_ROUND_1),
                Arguments.of(ReviewStage.WAITING_HUMAN, ReviewStage.COMPLETED),
                Arguments.of(ReviewStage.CANCELLED, ReviewStage.SNAPSHOTTING));
    }
}

