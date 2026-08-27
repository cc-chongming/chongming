package ai.cc.chongming.review.domain.protocol;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;

/**
 * [AIREVIEW-PLAN-003#1.3] Enforces the fixed lifecycle and cancellation/failure escape routes.
 *
 * @author wangli
 */
public final class ReviewStateMachine {

    private final Map<ReviewStage, Set<ReviewStage>> transitions;

    public ReviewStateMachine() {
        this.transitions = new EnumMap<>(ReviewStage.class);
        allow(ReviewStage.PENDING, ReviewStage.SNAPSHOTTING, ReviewStage.CANCELLING);
        allow(ReviewStage.SNAPSHOTTING, ReviewStage.PLANNING, ReviewStage.FAILED, ReviewStage.CANCELLING);
        allow(ReviewStage.PLANNING, ReviewStage.INITIAL_REVIEW, ReviewStage.FAILED, ReviewStage.CANCELLING);
        allow(ReviewStage.INITIAL_REVIEW, ReviewStage.CONFLICT_DETECTION, ReviewStage.FAILED, ReviewStage.CANCELLING);
        // [AIREVIEW-PLAN-047#1] New flow enters the single DEBATE phase; rounds are topic-level.
        allow(ReviewStage.CONFLICT_DETECTION, ReviewStage.DEBATE, ReviewStage.DEBATE_ROUND_1,
                ReviewStage.FAILED, ReviewStage.CANCELLING);
        allow(ReviewStage.DEBATE, ReviewStage.JUDGING, ReviewStage.FAILED, ReviewStage.CANCELLING);
        // [AIREVIEW-PLAN-047#1] Legacy DEBATE_ROUND_1/2 stays reachable so in-flight reviews keep
        // converging, and either legacy value may migrate into DEBATE when a plan-047 path resumes.
        // [AIREVIEW-PLAN-024#方案4] DEBATE_ROUND_1 may converge directly to JUDGING when every topic
        // is terminal and no valid second-round action remains; empty rounds are never fabricated.
        allow(ReviewStage.DEBATE_ROUND_1, ReviewStage.DEBATE_ROUND_2, ReviewStage.DEBATE,
                ReviewStage.JUDGING, ReviewStage.FAILED, ReviewStage.CANCELLING);
        allow(ReviewStage.DEBATE_ROUND_2, ReviewStage.DEBATE, ReviewStage.JUDGING,
                ReviewStage.FAILED, ReviewStage.CANCELLING);
        allow(ReviewStage.JUDGING, ReviewStage.WAITING_HUMAN, ReviewStage.FAILED, ReviewStage.CANCELLING);
        allow(ReviewStage.WAITING_HUMAN, ReviewStage.NOTIFYING, ReviewStage.FAILED, ReviewStage.CANCELLING);
        allow(ReviewStage.NOTIFYING, ReviewStage.COMPLETED, ReviewStage.FAILED, ReviewStage.CANCELLING);
        allow(ReviewStage.CANCELLING, ReviewStage.CANCELLED);
    }

    public ReviewStage transition(ReviewStage current, ReviewStage next) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(next, "next must not be null");
        if (!canTransition(current, next)) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "cannot transition from " + current + " to " + next);
        }
        return next;
    }

    public boolean canTransition(ReviewStage current, ReviewStage next) {
        return transitions.getOrDefault(current, Set.of()).contains(next);
    }

    private void allow(ReviewStage from, ReviewStage... nextStages) {
        transitions.put(from, EnumSet.copyOf(List.of(nextStages)));
    }
}

