package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.gate.GatePolicy;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewAssessmentStore;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-023#6] Verifies Judge conclusions and replayable AI Gate draft summaries.
 *
 * @author zyj
 */
class JudgeServiceTests {

    @Test
    void acceptsOnlyTopicClaimsAndPreservesAcceptedAndRejectedReferences() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        Review review = judgingReview();
        Claim productClaim = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT);
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE);
        Claim unrelatedClaim = claim(review.id(), RoleType.SECURITY, ClaimPosition.OPPOSE);
        store.saveClaim(productClaim);
        store.saveClaim(backendClaim);
        store.saveClaim(unrelatedClaim);
        DebateTopic topic = terminalTopic(review.id(), productClaim, backendClaim);
        store.saveTopic(topic);
        JudgeService service = new JudgeService(store);

        assertThatThrownBy(() -> service.submitJudgement(review, new JudgeService.JudgeSubmission(
                metadata(review, "judge-invalid"), topic.id(), GateResult.CONDITIONAL, "Invalid Claim reference.",
                List.of(unrelatedClaim.claimId()), List.of())))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ai.cc.chongming.review.domain.exception.ReviewErrorCode.TARGET_CLAIM_REQUIRED);

        JudgeService.JudgeResult accepted = service.submitJudgement(review, new JudgeService.JudgeSubmission(
                metadata(review, "judge-valid"), topic.id(), GateResult.CONDITIONAL, "Requirement needs a tracked condition.",
                List.of(productClaim.claimId()), List.of(backendClaim.claimId())));

        assertThat(accepted.decision().acceptedClaimIds()).containsExactly(productClaim.claimId());
        assertThat(accepted.decision().rejectedClaimIds()).containsExactly(backendClaim.claimId());
    }

    @Test
    void movesReviewToHumanWaitingWhenGateDraftRequiresHuman() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        Review review = judgingReview();
        Claim productClaim = claim(review.id(), RoleType.PRODUCT, ClaimPosition.OPPOSE).withStatus(ClaimStatus.UNVERIFIED);
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.SUPPORT);
        store.saveClaim(productClaim);
        store.saveClaim(backendClaim);
        DebateTopic topic = terminalTopic(review.id(), productClaim, backendClaim);
        store.saveTopic(topic);
        List<ReviewEventDraft> events = new ArrayList<>();
        JudgeService service = new JudgeService(store, new GatePolicy(), events::add);
        service.submitJudgement(review, new JudgeService.JudgeSubmission(
                metadata(review, "judge-human-required"), topic.id(), GateResult.CONDITIONAL,
                "Unverified high-severity evidence needs an operator.", List.of(productClaim.claimId()),
                List.of(backendClaim.claimId())));

        var draft = service.draftGate(review);

        assertThat(draft.result()).isEqualTo(GateResult.HUMAN_REQUIRED);
        assertThat(review.stage()).isEqualTo(ReviewStage.WAITING_HUMAN);
        assertThat(events).filteredOn(event -> event.type() == ReviewEventType.GATE_DRAFTED)
                .singleElement()
                .satisfies(event -> assertThat(event.payload())
                        .containsEntry("reasonSummary", draft.publicReasonSummary()));
    }

    @Test
    void movesReviewToHumanWaitingForEveryAiGateDraft() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        Review review = judgingReview();
        JudgeService service = new JudgeService(store);

        assertThat(service.draftGate(review).result()).isEqualTo(GateResult.AI_PASS);
        assertThat(review.stage()).isEqualTo(ReviewStage.WAITING_HUMAN);
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] The Gate consumes one batch assessment query plus the RolePack
     * required checkpoint set; without positive coverage AI_PASS is impossible.
     */
    @Test
    void gateDraftRequiresHumanWhenRequiredCheckpointCoverageIsMissing() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        Review review = judgingReview();
        ReviewAssessmentStore assessmentStore = new InMemoryReviewAssessmentStore();
        RolePackRegistry rolePackRegistry = new RolePackRegistry(new PathMatchingResourcePatternResolver());
        List<ReviewEventDraft> events = new ArrayList<>();
        JudgeService service =
                new JudgeService(store, new GatePolicy(), events::add, assessmentStore, rolePackRegistry);

        var draft = service.draftGate(review);

        assertThat(draft.result()).isEqualTo(GateResult.HUMAN_REQUIRED);
        assertThat(draft.publicReasonSummary())
                .contains("required checkpoint coverage incomplete")
                .contains("required=");
        assertThat(review.stage()).isEqualTo(ReviewStage.WAITING_HUMAN);
    }

    private DebateTopic terminalTopic(ReviewId reviewId, Claim productClaim, Claim backendClaim) {
        DebateStateMachine stateMachine = new DebateStateMachine();
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), reviewId, "authentication",
                List.of(productClaim.claimId(), backendClaim.claimId()));
        DebateTurn challenge = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), 1, RoleType.PRODUCT,
                RoleType.BACKEND, DebateTurnType.CHALLENGE, backendClaim.claimId(), null, "Please justify the policy.",
                List.of(), null, null, Instant.now());
        topic.addChallenge(stateMachine, challenge);
        DebateTurn rebuttal = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), 1, RoleType.BACKEND,
                RoleType.PRODUCT, DebateTurnType.REBUTTAL, null, challenge.turnId(), "No approved policy exists.",
                List.of(), null, null, Instant.now());
        topic.addRebuttal(stateMachine, rebuttal);
        topic.close(stateMachine, DebateTopicStatus.RESOLVED, "Policy requirement will be revised.", Instant.now());
        return topic;
    }

    private Review judgingReview() {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        review.transitionTo(stateMachine, ReviewStage.INITIAL_REVIEW);
        review.transitionTo(stateMachine, ReviewStage.CONFLICT_DETECTION);
        review.transitionTo(stateMachine, ReviewStage.DEBATE_ROUND_1);
        review.transitionTo(stateMachine, ReviewStage.DEBATE_ROUND_2);
        review.transitionTo(stateMachine, ReviewStage.JUDGING);
        return review;
    }

    private Claim claim(ReviewId reviewId, RoleType roleType, ClaimPosition position) {
        return new Claim(new ClaimId(UUID.randomUUID()), reviewId, roleType, "authentication", ClaimSeverity.P1, position,
                "Refresh-token policy", "Requirement statement", List.of());
    }

    private ReviewCommandMetadata metadata(Review review, String key) {
        return new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key));
    }
}
