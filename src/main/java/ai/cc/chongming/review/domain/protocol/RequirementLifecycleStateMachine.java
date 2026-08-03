package ai.cc.chongming.review.domain.protocol;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * [AIREVIEW-PLAN-021#1] Enforces requirement lifecycle transitions independently from review stages.
 *
 * @author zyj
 */
public final class RequirementLifecycleStateMachine {

    private final Map<RequirementStatus, Set<RequirementStatus>> transitions = new EnumMap<>(RequirementStatus.class);

    public RequirementLifecycleStateMachine() {
        allow(RequirementStatus.DRAFT, RequirementStatus.PENDING_REVIEW, RequirementStatus.CANCELLED);
        allow(RequirementStatus.PENDING_REVIEW, RequirementStatus.REVIEWING);
        allow(RequirementStatus.REVIEWING,
                RequirementStatus.APPROVED,
                RequirementStatus.REJECTED,
                RequirementStatus.RETURNED);
        allow(RequirementStatus.APPROVED, RequirementStatus.DEVELOPING);
        allow(RequirementStatus.RETURNED, RequirementStatus.PENDING_REVIEW);
        allow(RequirementStatus.DEVELOPING, RequirementStatus.DONE);
    }

    public RequirementStatus transition(RequirementStatus current, RequirementStatus next) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(next, "next must not be null");
        if (!canTransition(current, next)) {
            throw new RequirementDomainException(
                    RequirementErrorCode.ILLEGAL_LIFECYCLE_TRANSITION,
                    "cannot transition requirement from " + current + " to " + next);
        }
        return next;
    }

    public boolean canTransition(RequirementStatus current, RequirementStatus next) {
        return transitions.getOrDefault(current, Set.of()).contains(next);
    }

    private void allow(RequirementStatus from, RequirementStatus... nextStatuses) {
        transitions.put(from, EnumSet.copyOf(List.of(nextStatuses)));
    }
}
