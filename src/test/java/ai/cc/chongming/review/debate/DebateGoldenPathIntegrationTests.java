package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises two directed rounds, a terminal topic, Judge conclusion and non-final Gate draft.
 *
 * @author wangli
 */
class DebateGoldenPathIntegrationTests {

    @Test
    void producesTraceableConditionalGateDraftAfterTwoRounds() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        EvidenceLedgerService evidence = new EvidenceLedgerService();
        DebateService debateService = new DebateService(store, evidence, new DebateStateMachine());
        JudgeService judgeService = new JudgeService(store);
        Review review = conflictDetectionReview();
        Claim productClaim = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT);
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE);
        store.saveClaim(productClaim);
        store.saveClaim(backendClaim);

        DebateService.TopicResult opened = debateService.openTopic(review, new DebateToolCommands.OpenTopic(
                metadata(review, "open-topic"), RoleType.DIRECTOR, "authentication", List.of(productClaim.claimId(), backendClaim.claimId())));
        DebateService.TurnResult firstChallenge = debateService.submitChallenge(review, new DebateToolCommands.Challenge(
                metadata(review, "round-one-challenge"), RoleType.PRODUCT, RoleType.BACKEND, opened.topic().id(), 1,
                backendClaim.claimId(), "Explain the missing refresh-token behavior.", List.of(),
                "No evidence identifies the required refresh-token behavior."));
        debateService.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                metadata(review, "round-one-rebuttal"), RoleType.BACKEND, RoleType.PRODUCT, opened.topic().id(), 1,
                firstChallenge.turn().turnId(), "The backend requires an explicit policy before implementation.", List.of()));

        debateService.beginSecondRound(review);
        DebateService.TurnResult secondChallenge = debateService.submitChallenge(review, new DebateToolCommands.Challenge(
                metadata(review, "round-two-challenge"), RoleType.BACKEND, RoleType.PRODUCT, opened.topic().id(), 2,
                productClaim.claimId(), "Confirm the policy owner and expiry criteria.", List.of(),
                "No evidence establishes a policy owner or expiry criteria."));
        debateService.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                metadata(review, "round-two-rebuttal"), RoleType.PRODUCT, RoleType.BACKEND, opened.topic().id(), 2,
                secondChallenge.turn().turnId(), "Product will return the requirement with explicit expiry criteria.", List.of()));
        debateService.closeTopic(review, new DebateToolCommands.CloseTopic(
                metadata(review, "close-topic"), opened.topic().id(), DebateTopicStatus.RESOLVED, "Requirement must be revised."));
        debateService.beginJudging(review);

        JudgeService.JudgeResult judgement = judgeService.submitJudgement(review, new JudgeService.JudgeSubmission(
                metadata(review, "judge-topic"), opened.topic().id(), GateResult.CONDITIONAL,
                "Conditional until the revised token policy is accepted.", List.of(productClaim.claimId()),
                List.of(backendClaim.claimId())));
        GateDecision draft = judgeService.draftGate(review);

        assertThat(judgement.decision().result()).isEqualTo(GateResult.CONDITIONAL);
        assertThat(draft.result()).isEqualTo(GateResult.CONDITIONAL);
        assertThat(draft.status()).isEqualTo(DecisionStatus.DRAFT);
        assertThat(draft.actor()).isEqualTo(DecisionActor.AI);
        assertThat(opened.topic().turns()).hasSize(4);
    }

    @Test
    void reachesJudgingWithoutInventingATopicWhenClaimsHaveNoConflict() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        Review review = conflictDetectionReview();
        store.saveClaim(claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT));
        store.saveClaim(claim(review.id(), RoleType.BACKEND, ClaimPosition.SUPPORT));

        debateService.skipDebateWhenNoConflicts(review);

        assertThat(review.stage()).isEqualTo(ReviewStage.JUDGING);
        assertThat(store.findTopics(review.id())).isEmpty();
    }

    @Test
    void rejectsSkippingDebateWhenOpposingPositionExists() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        Review review = conflictDetectionReview();
        store.saveClaim(claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT));
        store.saveClaim(claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE));

        assertThatThrownBy(() -> debateService.skipDebateWhenNoConflicts(review))
                .isInstanceOf(ReviewDomainException.class)
                .hasMessageContaining("conflicting Claim positions");
    }

    private Review conflictDetectionReview() {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        review.transitionTo(stateMachine, ReviewStage.INITIAL_REVIEW);
        review.activateRole(new RoleActivation(RoleType.PRODUCT, "product-agent", true));
        review.activateRole(new RoleActivation(RoleType.PROJECT, "project-agent", true));
        review.activateRole(new RoleActivation(RoleType.FRONTEND, "frontend-agent", true));
        review.activateRole(new RoleActivation(RoleType.BACKEND, "backend-agent", true));
        review.transitionTo(stateMachine, ReviewStage.CONFLICT_DETECTION);
        return review;
    }

    private Claim claim(ReviewId reviewId, RoleType roleType, ClaimPosition position) {
        return new Claim(new ClaimId(UUID.randomUUID()), reviewId, roleType, "authentication", ClaimSeverity.P1, position,
                "Refresh token policy", "Requirement statement", List.of());
    }

    private ReviewCommandMetadata metadata(Review review, String key) {
        return new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key));
    }
}
