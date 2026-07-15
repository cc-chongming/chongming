package ai.cc.chongming.review.domain;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-003#1.4] Verifies the two-round debate topic lifecycle and references.
 *
 * @author wangli
 */
class DebateStateMachineTests {

    private final DebateStateMachine stateMachine = new DebateStateMachine();

    @Test
    void permitsTwoRoundsThenResolution() {
        DebateTopicStatus status = stateMachine.transition(DebateTopicStatus.OPEN, DebateTopicStatus.CHALLENGED);
        status = stateMachine.transition(status, DebateTopicStatus.REBUTTED);
        status = stateMachine.transition(status, DebateTopicStatus.CHALLENGED);
        status = stateMachine.transition(status, DebateTopicStatus.REBUTTED);
        status = stateMachine.transition(status, DebateTopicStatus.RESOLVED);

        assertThat(status).isEqualTo(DebateTopicStatus.RESOLVED);
    }

    @Test
    void rejectsAThirdChallengeRound() {
        assertThatThrownBy(() -> stateMachine.validateRound(3))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(error -> ((ReviewDomainException) error).errorCode())
                .isEqualTo(ReviewErrorCode.DEBATE_ROUND_EXCEEDED);
    }

    @Test
    void rejectsChallengeWithoutTargetClaim() {
        assertThatThrownBy(() -> stateMachine.validateChallenge(1, null))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(error -> ((ReviewDomainException) error).errorCode())
                .isEqualTo(ReviewErrorCode.TARGET_CLAIM_REQUIRED);
    }

    @Test
    void rejectsRebuttalWithoutTargetTurn() {
        assertThatThrownBy(() -> stateMachine.validateRebuttal(1, null))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(error -> ((ReviewDomainException) error).errorCode())
                .isEqualTo(ReviewErrorCode.TARGET_TURN_REQUIRED);
    }

    @Test
    void acceptsExistingReferencesForChallengeAndRebuttal() {
        stateMachine.validateChallenge(2, new ClaimId(UUID.randomUUID()));
        stateMachine.validateRebuttal(2, new TurnId(UUID.randomUUID()));
    }
}

