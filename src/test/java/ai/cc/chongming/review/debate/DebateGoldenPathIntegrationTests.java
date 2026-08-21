package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.application.ConflictDetectionService;
import ai.cc.chongming.review.application.ReviewEventPublisher;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewConflictAuditStore;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-024#方案4] Exercises batch topic registration, two directed rounds, a terminal
 * topic, Judge conclusion and non-final Gate draft, plus the PLAN-024 validation matrix: atomic
 * multi-topic registration with a single stage migration, directed rebuttal identity and
 * round-two convergence without an empty round.
 *
 * @author zyj
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

        DebateService.RegisterTopicsResult registered = debateService.registerTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "register-topics"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("authentication",
                                List.of(productClaim.claimId(), backendClaim.claimId())))));
        DebateTopic opened = registered.topics().get(0).topic();
        DebateService.TurnResult firstChallenge = debateService.submitChallenge(review, new DebateToolCommands.Challenge(
                metadata(review, "round-one-challenge"), RoleType.PRODUCT, RoleType.BACKEND, opened.id(), 1,
                backendClaim.claimId(), "Explain the missing refresh-token behavior.", List.of(),
                "No evidence identifies the required refresh-token behavior."));
        debateService.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                metadata(review, "round-one-rebuttal"), RoleType.BACKEND, RoleType.PRODUCT, opened.id(), 1,
                firstChallenge.turn().turnId(), "The backend requires an explicit policy before implementation.", List.of()));
        // A round-one evidence request answered only in round two keeps a valid open action, so the
        // second round is not skipped as an empty round.
        debateService.requestAdditionalEvidence(review, new DebateToolCommands.EvidenceRequest(
                metadata(review, "round-one-evidence-request"), RoleType.BACKEND, RoleType.PRODUCT, opened.id(), 1,
                productClaim.claimId(), "Provide the signed requirement that defines the token policy."));

        debateService.beginSecondRound(review);
        DebateService.TurnResult secondChallenge = debateService.submitChallenge(review, new DebateToolCommands.Challenge(
                metadata(review, "round-two-challenge"), RoleType.BACKEND, RoleType.PRODUCT, opened.id(), 2,
                productClaim.claimId(), "Confirm the policy owner and expiry criteria.", List.of(),
                "No evidence establishes a policy owner or expiry criteria."));
        debateService.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                metadata(review, "round-two-rebuttal"), RoleType.PRODUCT, RoleType.BACKEND, opened.id(), 2,
                secondChallenge.turn().turnId(), "Product will return the requirement with explicit expiry criteria.", List.of()));
        debateService.closeTopic(review, new DebateToolCommands.CloseTopic(
                metadata(review, "close-topic"), opened.id(), DebateTopicStatus.RESOLVED, "Requirement must be revised."));
        debateService.beginJudging(review);

        JudgeService.JudgeResult judgement = judgeService.submitJudgement(review, new JudgeService.JudgeSubmission(
                metadata(review, "judge-topic"), opened.id(), GateResult.CONDITIONAL,
                "Conditional until the revised token policy is accepted.", List.of(productClaim.claimId()),
                List.of(backendClaim.claimId())));
        GateDecision draft = judgeService.draftGate(review);

        assertThat(judgement.decision().result()).isEqualTo(GateResult.CONDITIONAL);
        assertThat(draft.result()).isEqualTo(GateResult.CONDITIONAL);
        assertThat(draft.status()).isEqualTo(DecisionStatus.DRAFT);
        assertThat(draft.actor()).isEqualTo(DecisionActor.AI);
        // Challenge/rebuttal pairs live on the topic aggregate; the round-one evidence request is
        // persisted in the store, so the store view holds all five turns.
        assertThat(store.findTopic(review.id(), opened.id()).orElseThrow().turns()).hasSize(4);
        assertThat(store.findTurns(review.id(), opened.id())).hasSize(5);
    }

    /** [AIREVIEW-PLAN-024#方案4 验证矩阵] N 个候选原子登记 N 主题，阶段只迁移一次，重复调用幂等。 */
    @Test
    void registersMultipleTopicsAtomicallyWithExactlyOneStageMigration() {
        BatchOnlyReviewDebateStore store = new BatchOnlyReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        Review review = conflictDetectionReview();
        Claim authSupport = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT, "authentication");
        Claim authOppose = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE, "authentication");
        Claim securityOppose = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE, "mcp.security");
        store.saveClaim(authSupport);
        store.saveClaim(authOppose);
        store.saveClaim(securityOppose);

        DebateService.RegisterTopicsResult result = debateService.registerTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "register-all"), RoleType.DIRECTOR, List.of(
                        new DebateToolCommands.TopicProposal("authentication",
                                List.of(authSupport.claimId(), authOppose.claimId())),
                        // Duplicate subject (case/whitespace variant) must deduplicate, not register twice.
                        new DebateToolCommands.TopicProposal(" Authentication ", List.of(authOppose.claimId())),
                        new DebateToolCommands.TopicProposal("mcp.security", List.of(securityOppose.claimId())))));

        assertThat(result.replayed()).isFalse();
        assertThat(result.topics()).hasSize(2);
        assertThat(store.findTopics(review.id())).hasSize(2);
        assertThat(store.batchWrites).isEqualTo(1);
        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);

        // Replaying the same idempotency key returns the registered topics without a second migration.
        DebateService.RegisterTopicsResult replayed = debateService.registerTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "register-all"), RoleType.DIRECTOR, List.of(
                        new DebateToolCommands.TopicProposal("authentication",
                                List.of(authSupport.claimId(), authOppose.claimId())),
                        new DebateToolCommands.TopicProposal("mcp.security", List.of(securityOppose.claimId())))));
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.topics()).hasSize(2);
        assertThat(store.findTopics(review.id())).hasSize(2);
        assertThat(store.batchWrites).isEqualTo(1);
        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);
    }

    @Test
    void leavesReviewAndTopicsUntouchedWhenConflictAuditFinalizationFails() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        ConflictDetectionService conflicts = new ConflictDetectionService(
                new InMemoryReviewAssessmentStore(), store, new FailingConflictAuditStore());
        DebateService debateService = new DebateService(
                store,
                new EvidenceLedgerService(),
                new DebateStateMachine(),
                new ReviewProtocolGuard(),
                ReviewEventPublisher.noop(),
                conflicts);
        Review review = conflictDetectionReview();
        Claim support = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT);
        Claim oppose = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE);
        store.saveClaim(support);
        store.saveClaim(oppose);
        DebateToolCommands.RegisterTopics command = new DebateToolCommands.RegisterTopics(
                metadata(review, "audit-failure"),
                RoleType.DIRECTOR,
                List.of(new DebateToolCommands.TopicProposal(
                        "authentication", List.of(support.claimId(), oppose.claimId()))));

        assertThatThrownBy(() -> debateService.registerTopics(review, command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit unavailable");
        assertThat(store.findTopics(review.id())).isEmpty();
        assertThat(review.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
        assertThat(review.commandResults()).doesNotContainKey(command.metadata().idempotencyKey());
    }

    /** [AIREVIEW-PLAN-024#方案4 验证矩阵] 定向反驳：只有 challenge.targetRole 可回应，第三方被拒且状态不变。 */
    @Test
    void rejectsThirdPartyRebuttalAndKeepsTopicStateUnchanged() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        Review review = conflictDetectionReview();
        Claim productClaim = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT);
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE);
        store.saveClaim(productClaim);
        store.saveClaim(backendClaim);
        DebateTopic opened = debateService.registerTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "register-directed"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("authentication",
                                List.of(productClaim.claimId(), backendClaim.claimId())))))
                .topics().get(0).topic();
        DebateService.TurnResult challenge = debateService.submitChallenge(review, new DebateToolCommands.Challenge(
                metadata(review, "challenge-for-identity"), RoleType.PRODUCT, RoleType.BACKEND, opened.id(), 1,
                backendClaim.claimId(), "Explain the refresh-token handling.", List.of(),
                "No evidence covers refresh-token handling."));
        DebateTopic before = store.findTopic(review.id(), opened.id()).orElseThrow();

        // PROJECT is a third party: it is neither the challenged role nor the challenger.
        assertThatThrownBy(() -> debateService.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                metadata(review, "third-party-rebuttal"), RoleType.PROJECT, RoleType.PRODUCT, opened.id(), 1,
                challenge.turn().turnId(), "Project answers on behalf of backend.", List.of())))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.DISPATCH_ACTOR_MISMATCH);

        DebateTopic after = store.findTopic(review.id(), opened.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.turns()).hasSize(before.turns().size());
        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);

        // The challenged role itself may answer, keeping the challenge's true turn id as target.
        DebateService.TurnResult rebuttal = debateService.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                metadata(review, "directed-rebuttal"), RoleType.BACKEND, RoleType.PRODUCT, opened.id(), 1,
                challenge.turn().turnId(), "Backend answers with the deployment policy.", List.of()));
        assertThat(rebuttal.turn().targetTurnId()).isEqualTo(challenge.turn().turnId());
    }

    /** [AIREVIEW-PLAN-024#方案4 验证矩阵] 第二轮收敛：无有效动作禁止空回合，可直接从第一轮进入裁决。 */
    @Test
    void convergesToJudgingFromRoundOneWhenNoOpenActionRemains() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        Review review = conflictDetectionReview();
        Claim productClaim = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT);
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE);
        store.saveClaim(productClaim);
        store.saveClaim(backendClaim);
        DebateTopic opened = debateService.registerTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "register-converge"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("authentication",
                                List.of(productClaim.claimId(), backendClaim.claimId())))))
                .topics().get(0).topic();
        debateService.closeTopic(review, new DebateToolCommands.CloseTopic(
                metadata(review, "close-early"), opened.id(), DebateTopicStatus.ESCALATED, "Escalated during round one."));

        assertThatThrownBy(() -> debateService.beginSecondRound(review))
                .isInstanceOf(ReviewDomainException.class)
                .hasMessageContaining("empty second round");

        debateService.beginJudging(review);
        assertThat(review.stage()).isEqualTo(ReviewStage.JUDGING);
    }

    /** [AIREVIEW-PLAN-024#方案4 验证矩阵] 第二轮动作回应第一轮 Turn 时保留其真实 targetTurnId。 */
    @Test
    void roundTwoRebuttalKeepsTheRoundOneTargetTurnId() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        Review review = conflictDetectionReview();
        Claim productClaim = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT);
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE);
        store.saveClaim(productClaim);
        store.saveClaim(backendClaim);
        DebateTopic opened = debateService.registerTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "register-cross-round"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("authentication",
                                List.of(productClaim.claimId(), backendClaim.claimId())))))
                .topics().get(0).topic();
        DebateService.TurnResult roundOneChallenge = debateService.submitChallenge(review, new DebateToolCommands.Challenge(
                metadata(review, "cross-round-challenge"), RoleType.PRODUCT, RoleType.BACKEND, opened.id(), 1,
                backendClaim.claimId(), "Provide the refresh-token contract.", List.of(),
                "No evidence defines the refresh-token contract."));
        debateService.beginSecondRound(review);

        DebateService.TurnResult lateRebuttal = debateService.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                metadata(review, "cross-round-rebuttal"), RoleType.BACKEND, RoleType.PRODUCT, opened.id(), 2,
                roundOneChallenge.turn().turnId(), "Backend answers the round-one challenge in round two.", List.of()));

        assertThat(lateRebuttal.turn().round()).isEqualTo(2);
        assertThat(lateRebuttal.turn().targetTurnId()).isEqualTo(roundOneChallenge.turn().turnId());
        assertThat(roundOneChallenge.turn().round()).isEqualTo(1);
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
                .hasMessageContaining("no deterministic conflict candidate remains");
    }

    /**
     * [AIREVIEW-PLAN-024#方案4 收口 / 2026-08-19 修订] Even without a SUPPORT pair forming a
     * deterministic candidate, a lone unwithdrawn OPPOSE Claim must keep the review in debate:
     * the server enforces the Director prompt's "skip only when no OPPOSE remains" promise.
     */
    @Test
    void rejectsSkippingDebateWhenOnlyUnwithdrawnOpposeClaimsRemain() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        Review review = conflictDetectionReview();
        store.saveClaim(claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE));

        assertThatThrownBy(() -> debateService.skipDebateWhenNoConflicts(review))
                .isInstanceOf(ReviewDomainException.class)
                .hasMessageContaining("no unwithdrawn OPPOSE claim remains");
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
        return claim(reviewId, roleType, position, "authentication");
    }

    private Claim claim(ReviewId reviewId, RoleType roleType, ClaimPosition position, String subjectKey) {
        return new Claim(new ClaimId(UUID.randomUUID()), reviewId, roleType, subjectKey, ClaimSeverity.P1, position,
                "Refresh token policy", "Requirement statement", List.of());
    }

    private ReviewCommandMetadata metadata(Review review, String key) {
        return new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key));
    }

    /** @author zyj */
    private static final class BatchOnlyReviewDebateStore extends InMemoryReviewDebateStore {

        private int batchWrites;

        @Override
        public void saveTopic(DebateTopic topic) {
            throw new AssertionError("multi-topic registration must use one batch write");
        }

        @Override
        public void saveTopics(List<DebateTopic> topics) {
            batchWrites++;
            super.saveTopics(topics);
        }
    }

    /** @author zyj */
    private static final class FailingConflictAuditStore implements ReviewConflictAuditStore {

        @Override
        public void replaceBatch(
                ReviewId reviewId,
                int attemptNo,
                Collection<ai.cc.chongming.review.domain.model.ReviewConflictAudit> records) {
        }

        @Override
        public void finalizeAttempt(
                ReviewId reviewId,
                int attemptNo,
                Collection<String> registeredSubjectKeys,
                Instant updatedAt) {
            throw new IllegalStateException("audit unavailable");
        }

        @Override
        public List<ai.cc.chongming.review.domain.model.ReviewConflictAudit> findByReviewAttempt(
                ReviewId reviewId, int attemptNo) {
            return List.of();
        }
    }
}
