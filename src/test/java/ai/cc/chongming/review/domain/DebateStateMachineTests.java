package ai.cc.chongming.review.domain;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;

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

    @Test
    void requiresSecondRoundForOpenAndChallengedTopicsWithoutClaims() {
        DebateTopic open = new DebateTopic(new TopicId(UUID.randomUUID()), new ReviewId(UUID.randomUUID()),
                "authentication", List.of());
        assertThat(stateMachine.requiresSecondRoundAction(open)).isTrue();

        DebateTopic challenged = DebateTopic.restore(new TopicId(UUID.randomUUID()),
                new ReviewId(UUID.randomUUID()), "authentication", List.of(), DebateTopicStatus.CHALLENGED,
                1, List.of(), null, null);
        assertThat(stateMachine.requiresSecondRoundAction(challenged)).isTrue();
    }

    @Test
    void terminalTopicNeverRequiresSecondRoundEvenWithP0OpposeClaim() {
        DebateTopic resolved = DebateTopic.restore(new TopicId(UUID.randomUUID()),
                new ReviewId(UUID.randomUUID()), "authentication", List.of(), DebateTopicStatus.RESOLVED,
                1, List.of(), null, null);
        Claim p0 = claim(ClaimSeverity.P0, ClaimPosition.OPPOSE, ClaimStatus.SUBMITTED);
        assertThat(stateMachine.requiresSecondRoundAction(resolved, List.of(p0))).isFalse();
    }

    @Test
    void rebuttedTopicWithUnwithdrawnP0OrP1OpposeClaimRequiresSecondRound() {
        DebateTopic rebutted = rebuttedTopic();
        Claim p1 = claim(ClaimSeverity.P1, ClaimPosition.OPPOSE, ClaimStatus.SUBMITTED);
        assertThat(stateMachine.requiresSecondRoundAction(rebutted, List.of(p1))).isTrue();
        Claim p0 = claim(ClaimSeverity.P0, ClaimPosition.OPPOSE, ClaimStatus.UNVERIFIED);
        assertThat(stateMachine.requiresSecondRoundAction(rebutted, List.of(p0))).isTrue();
    }

    @Test
    void rebuttedTopicWithoutValidP0P1OpposeDoesNotRequireSecondRound() {
        DebateTopic rebutted = rebuttedTopic();
        Claim p2 = claim(ClaimSeverity.P2, ClaimPosition.OPPOSE, ClaimStatus.SUBMITTED);
        Claim p3 = claim(ClaimSeverity.P3, ClaimPosition.OPPOSE, ClaimStatus.SUBMITTED);
        assertThat(stateMachine.requiresSecondRoundAction(rebutted, List.of(p2, p3))).isFalse();
        Claim withdrawnP1 = claim(ClaimSeverity.P1, ClaimPosition.OPPOSE, ClaimStatus.WITHDRAWN);
        assertThat(stateMachine.requiresSecondRoundAction(rebutted, List.of(withdrawnP1))).isFalse();
    }

    private DebateTopic rebuttedTopic() {
        return DebateTopic.restore(new TopicId(UUID.randomUUID()), new ReviewId(UUID.randomUUID()),
                "authentication", List.of(), DebateTopicStatus.REBUTTED, 1, List.of(), null, null);
    }

    private Claim claim(ClaimSeverity severity, ClaimPosition position, ClaimStatus status) {
        return new Claim(new ClaimId(UUID.randomUUID()), new ReviewId(UUID.randomUUID()), RoleType.BACKEND,
                "authentication", severity, position, "后端反对该方案。", "存在残余风险。", List.of(), status);
    }
}

