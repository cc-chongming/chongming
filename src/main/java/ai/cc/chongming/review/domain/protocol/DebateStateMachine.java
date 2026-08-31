package ai.cc.chongming.review.domain.protocol;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import static ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;

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

    /**
     * [AIREVIEW-PLAN-024#方案4] The second round may begin only while at least one valid open action
     * remains: a challenge awaiting its answer before the Judge, an unanswered evidence request, or a
     * non-terminal topic whose conflicting positions are not yet clarified. Without any open action the
     * Director must converge straight to judging instead of running an empty round.
     */
    public boolean hasOpenSecondRoundActions(Collection<DebateTopic> topics) {
        Objects.requireNonNull(topics, "topics must not be null");
        return topics.stream().anyMatch(this::requiresSecondRoundAction);
    }

    /**
     * [AIREVIEW-PLAN-024#方案4][AIREVIEW-PLAN-082#1] One topic's second-round requirement:
     * challenged topics must receive the rebuttal, open topics still hold unclarified conflicting
     * positions, and any topic with an unanswered evidence request owes its target role one answer.
     * This single-topic overload keeps the legacy evidence-request-only view by passing no claims.
     */
    public boolean requiresSecondRoundAction(DebateTopic topic) {
        Objects.requireNonNull(topic, "topic must not be null");
        return requiresSecondRoundAction(topic, List.of());
    }

    /**
     * [AIREVIEW-PLAN-082#1] Same requirement enriched with the topic's resolved claims. REBUTTED
     * topics no longer converge solely on an empty action queue: an unwithdrawn P0/P1 OPPOSE claim
     * means the substantive disagreement is still open and deserves a second round.
     */
    public boolean requiresSecondRoundAction(
            DebateTopic topic,
            java.util.Collection<ai.cc.chongming.review.domain.model.Claim> claims) {
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(claims, "claims must not be null");
        if (topic.status().isTerminal()) {
            return false;
        }
        if (topic.status() == DebateTopicStatus.OPEN || topic.status() == DebateTopicStatus.CHALLENGED) {
            return true;
        }
        if (hasUnansweredEvidenceRequest(topic.turns())) {
            return true;
        }
        return claims.stream().anyMatch(claim -> claim.position() == ClaimPosition.OPPOSE
                && (claim.severity() == ClaimSeverity.P0 || claim.severity() == ClaimSeverity.P1)
                && claim.status() != ClaimStatus.WITHDRAWN);
    }

    private boolean hasUnansweredEvidenceRequest(List<DebateTurn> turns) {
        for (int index = 0; index < turns.size(); index++) {
            DebateTurn turn = turns.get(index);
            if (turn.turnType() != DebateTurnType.EVIDENCE_REQUEST || turn.targetRole() == null) {
                continue;
            }
            boolean answered = false;
            for (int later = index + 1; later < turns.size(); later++) {
                if (turns.get(later).actorRole() == turn.targetRole()) {
                    answered = true;
                    break;
                }
            }
            if (!answered) {
                return true;
            }
        }
        return false;
    }

    private void allow(DebateTopicStatus from, DebateTopicStatus... nextStatuses) {
        transitions.put(from, EnumSet.copyOf(List.of(nextStatuses)));
    }
}

