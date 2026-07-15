package ai.cc.chongming.review.domain.protocol;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import static ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;

/**
 * [AIREVIEW-PLAN-003#1.4] Enforces the bounded debate topic lifecycle and mandatory references.
 *
 * @author wangli
 */
public final class DebateStateMachine {

    private static final int MAX_ROUNDS = 2;
    private final Map<DebateTopicStatus, Set<DebateTopicStatus>> transitions = new EnumMap<>(DebateTopicStatus.class);

    public DebateStateMachine() {
        allow(DebateTopicStatus.OPEN, DebateTopicStatus.CHALLENGED, DebateTopicStatus.ESCALATED);
        allow(DebateTopicStatus.CHALLENGED, DebateTopicStatus.REBUTTED, DebateTopicStatus.ESCALATED);
        allow(DebateTopicStatus.REBUTTED, DebateTopicStatus.CHALLENGED, DebateTopicStatus.RESOLVED,
                DebateTopicStatus.ESCALATED);
    }

    public DebateTopicStatus transition(DebateTopicStatus current, DebateTopicStatus next) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(next, "next must not be null");
        if (!transitions.getOrDefault(current, Set.of()).contains(next)) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "cannot transition debate topic from " + current + " to " + next);
        }
        return next;
    }

    public void validateRound(int round) {
        if (round < 1 || round > MAX_ROUNDS) {
            throw new ReviewDomainException(ReviewErrorCode.DEBATE_ROUND_EXCEEDED,
                    "debate round must be between 1 and " + MAX_ROUNDS);
        }
    }

    public void validateChallenge(int round, ClaimId targetClaimId) {
        validateRound(round);
        if (targetClaimId == null) {
            throw new ReviewDomainException(ReviewErrorCode.TARGET_CLAIM_REQUIRED,
                    "a challenge requires targetClaimId");
        }
    }

    public void validateRebuttal(int round, TurnId targetTurnId) {
        validateRound(round);
        if (targetTurnId == null) {
            throw new ReviewDomainException(ReviewErrorCode.TARGET_TURN_REQUIRED,
                    "a rebuttal requires targetTurnId");
        }
    }

    private void allow(DebateTopicStatus from, DebateTopicStatus... nextStatuses) {
        transitions.put(from, EnumSet.copyOf(List.of(nextStatuses)));
    }
}

