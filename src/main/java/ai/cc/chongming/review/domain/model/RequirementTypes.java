package ai.cc.chongming.review.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-021#1] Defines value types for the requirement lifecycle aggregate.
 *
 * @author zyj
 */
public final class RequirementTypes {

    private RequirementTypes() {
    }

    /**
     * @author zyj
     */
    public record RequirementId(UUID value) {
        public RequirementId {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * @author zyj
     */
    public enum RequirementStatus {
        DRAFT,
        PENDING_REVIEW,
        REVIEWING,
        APPROVED,
        REJECTED,
        RETURNED,
        DEVELOPING,
        DONE,
        CANCELLED;

        public boolean isTerminal() {
            return this == REJECTED || this == DONE || this == CANCELLED;
        }
    }

    /**
     * @author zyj
     */
    public enum RequirementLifecycleEvent {
        CREATED,
        SUBMITTED,
        REVIEW_STARTED,
        APPROVED,
        REJECTED,
        RETURNED,
        DEVELOPING_STARTED,
        DONE,
        CANCELLED
    }
}
