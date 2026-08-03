package ai.cc.chongming.review.domain;

import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementLifecycleEvent;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.protocol.RequirementLifecycleStateMachine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-021#1] Protects requirement aggregate defaults and versioned review binding.
 *
 * @author zyj
 */
class RequirementTests {

    @Test
    void createsDraftAndBindsItsReviewOnlyWhenSubmitted() {
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()),
                "统一身份资料同步",
                "# 需求\n\n需要同步学生基础身份。",
                "alice",
                "bob",
                "cx-ai",
                "P1");

        assertThat(requirement.status()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(requirement.reviewId()).isNull();
        assertThat(requirement.version()).isZero();

        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        requirement.submitForReview(reviewId, new RequirementLifecycleStateMachine());

        assertThat(requirement.status()).isEqualTo(RequirementStatus.PENDING_REVIEW);
        assertThat(requirement.reviewId()).isEqualTo(reviewId);
        assertThat(requirement.version()).isEqualTo(1L);
    }

    @Test
    void bindsANewReviewWhenReturnedRequirementIsResubmitted() {
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "身份同步", "同步资料", "alice", null, "cx-ai", "P1");
        RequirementLifecycleStateMachine stateMachine = new RequirementLifecycleStateMachine();
        ReviewId firstReview = new ReviewId(UUID.randomUUID());
        ReviewId secondReview = new ReviewId(UUID.randomUUID());
        requirement.submitForReview(firstReview, stateMachine);
        requirement.transitionTo(RequirementStatus.REVIEWING, stateMachine);
        requirement.transitionTo(RequirementStatus.RETURNED, stateMachine);

        requirement.submitForReview(secondReview, stateMachine);

        assertThat(requirement.status()).isEqualTo(RequirementStatus.PENDING_REVIEW);
        assertThat(requirement.reviewId()).isEqualTo(secondReview);
    }

    @Test
    void rejectsRevisionOutsideDraftOrReturnedWithStableLifecycleCode() {
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "身份同步", "同步资料", "alice", null, "cx-ai", "P1");
        RequirementLifecycleStateMachine stateMachine = new RequirementLifecycleStateMachine();
        requirement.submitForReview(new ReviewId(UUID.randomUUID()), stateMachine);

        assertThatThrownBy(() -> requirement.revise("新标题", "", null, "cx-ai", "P1", requirement.version()))
                .isInstanceOf(RequirementDomainException.class)
                .extracting(exception -> ((RequirementDomainException) exception).errorCode())
                .isEqualTo(RequirementErrorCode.ILLEGAL_LIFECYCLE_TRANSITION);
    }

    @Test
    void exposesRequirementLifecycleValueTypesWithTheirDocumentedSemantics() {
        assertThatThrownBy(() -> new RequirementId(null)).isInstanceOf(NullPointerException.class);
        assertThat(RequirementStatus.REJECTED.isTerminal()).isTrue();
        assertThat(RequirementStatus.DONE.isTerminal()).isTrue();
        assertThat(RequirementStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RequirementStatus.DRAFT.isTerminal()).isFalse();
        assertThat(RequirementLifecycleEvent.values()).containsExactly(
                RequirementLifecycleEvent.CREATED,
                RequirementLifecycleEvent.SUBMITTED,
                RequirementLifecycleEvent.REVIEW_STARTED,
                RequirementLifecycleEvent.APPROVED,
                RequirementLifecycleEvent.REJECTED,
                RequirementLifecycleEvent.RETURNED,
                RequirementLifecycleEvent.DEVELOPING_STARTED,
                RequirementLifecycleEvent.DONE,
                RequirementLifecycleEvent.CANCELLED);
    }
}
