package ai.cc.chongming.review.human;

import ai.cc.chongming.review.application.HumanGateDecisionService;
import ai.cc.chongming.review.application.ReviewEventService;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionActor;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.Permission;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import ai.cc.chongming.review.infrastructure.human.InMemoryHumanGateDecisionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [AIREVIEW-PLAN-011#1.3] Verifies final human Gate versioning, permissions and stage transition.
 *
 * @author wangli
 */
class HumanGateDecisionServiceTests {

    @Test
    void finalizesHumanDecisionFromAiDraftAndMovesToNotification() {
        Fixture fixture = new Fixture(reviewer(Set.of(Permission.REVIEW)));
        Review review = waitingHumanReview(fixture.reviewId, 4L);
        fixture.debateStore.saveGateDraft(aiDraft(fixture.reviewId));

        HumanGateDecision decision = fixture.service.finalizeDecision(
                review,
                new HumanGateDecisionService.FinalDecisionCommand(4L, GateResult.CONDITIONAL,
                        "Allow deployment after remediation", List.of("Add an authorization check"), null));

        assertEquals(1L, decision.gateVersion());
        assertEquals(ReviewStage.NOTIFYING, review.stage());
        assertEquals(5L, review.version());
        assertEquals(List.of(decision), fixture.service.findVersions(review));
        assertEquals(ReviewEventType.HUMAN_GATE_FINALIZED,
                fixture.events.replay(fixture.reviewId, 0L, 10).getFirst().type());
    }

    @Test
    void rejectsStaleVersionAndUnauthorizedOverride() {
        Fixture fixture = new Fixture(reviewer(Set.of(Permission.REVIEW)));
        Review review = waitingHumanReview(fixture.reviewId, 0L);
        fixture.debateStore.saveGateDraft(aiDraft(fixture.reviewId));

        assertThrows(IllegalStateException.class,
                () -> fixture.service.finalizeDecision(review,
                        new HumanGateDecisionService.FinalDecisionCommand(1L, GateResult.PASS, "approved", List.of(), null)));
        assertThrows(SecurityException.class,
                () -> fixture.service.finalizeDecision(review,
                        new HumanGateDecisionService.FinalDecisionCommand(0L, GateResult.OVERRIDE, "approved", List.of(),
                                "Business exception approved")));
    }

    @Test
    void appendsRevisionInsteadOfOverwritingFinalGateWhileNotificationIsPending() {
        Fixture fixture = new Fixture(reviewer(Set.of(Permission.REVIEW)));
        Review review = waitingHumanReview(fixture.reviewId, 4L);
        fixture.debateStore.saveGateDraft(aiDraft(fixture.reviewId));

        HumanGateDecision first = fixture.service.finalizeDecision(review,
                new HumanGateDecisionService.FinalDecisionCommand(4L, GateResult.CONDITIONAL,
                        "remediation required", List.of("add authorization"), null));
        HumanGateDecision revision = fixture.service.finalizeDecision(review,
                new HumanGateDecisionService.FinalDecisionCommand(5L, GateResult.PASS,
                        "remediation verified", List.of(), null));

        assertEquals(2L, revision.gateVersion());
        assertEquals(1L, revision.supersedesVersion());
        assertEquals(ReviewStage.NOTIFYING, review.stage());
        assertEquals(6L, review.version());
        assertEquals(List.of(first, revision), fixture.service.findVersions(review));
    }
    @Test
    void conditionalAndOverrideValidationIsImmutableAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new HumanGateDecision(
                        new ReviewId(UUID.randomUUID()), 1L, GateResult.CONDITIONAL, "reason", List.of(), null,
                        "reviewer", null, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new HumanGateDecision(
                        new ReviewId(UUID.randomUUID()), 1L, GateResult.OVERRIDE, "reason", List.of(), null,
                        "reviewer", null, Instant.now()));
    }

    private GateDecision aiDraft(ReviewId reviewId) {
        return new GateDecision(reviewId, GateResult.HUMAN_REQUIRED, DecisionStatus.DRAFT, DecisionActor.AI,
                "AI requests human decision", Instant.now());
    }

    private Review waitingHumanReview(ReviewId reviewId, long version) {
        return Review.restore(reviewId, ReviewStage.WAITING_HUMAN, 1, version, List.of(), Map.of());
    }

    private ReviewerIdentityProvider reviewer(Set<Permission> permissions) {
        return () -> new ReviewerIdentity("reviewer-1", permissions);
    }

    private static final class Fixture {
        private final ReviewId reviewId = new ReviewId(UUID.randomUUID());
        private final InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
        private final ReviewEventService events = new ReviewEventService(new InMemoryReviewEventStore());
        private final HumanGateDecisionService service;

        private Fixture(ReviewerIdentityProvider identityProvider) {
            service = new HumanGateDecisionService(
                    new InMemoryHumanGateDecisionStore(),
                    debateStore,
                    identityProvider,
                    new ReviewProtocolGuard(),
                    new ReviewStateMachine(),
                    events);
        }
    }
}
