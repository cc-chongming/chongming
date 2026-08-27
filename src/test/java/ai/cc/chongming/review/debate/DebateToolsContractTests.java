package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.application.ClaimService;
import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateTools;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the AgentScope-facing facade retains server-side turn and round invariants.
 *
 * @author wangli
 */
class DebateToolsContractTests {

    @Test
    void recordsDirectedEvidenceRequestAndRejectsRepeatedRoundTwoChallenge() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        EvidenceLedgerService evidenceLedger = new EvidenceLedgerService();
        DebateService debateService = new DebateService(store, evidenceLedger, new DebateStateMachine());
        DebateTools tools = new DebateTools(
                new ClaimService(evidenceLedger, store, new ReviewProtocolGuard()), debateService, new JudgeService(store));
        Review review = conflictDetectionReview();
        Claim productClaim = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT);
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE);
        store.saveClaim(productClaim);
        store.saveClaim(backendClaim);

        DebateService.RegisterTopicsResult registered = tools.registerDebateTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "open"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("authentication",
                                List.of(productClaim.claimId(), backendClaim.claimId())))));
        DebateService.TopicResult opened = registered.topics().get(0);
        DebateService.TurnResult challenge = tools.submitChallenge(review, new DebateToolCommands.Challenge(
                metadata(review, "challenge-round-one"), RoleType.PRODUCT, RoleType.BACKEND, opened.topic().id(), 1,
                backendClaim.claimId(), "Provide evidence for the refresh-token implementation.", List.of(),
                "The initial requirement does not cite an implementation artifact."));
        tools.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                metadata(review, "rebuttal-round-one"), RoleType.BACKEND, RoleType.PRODUCT, opened.topic().id(), 1,
                challenge.turn().turnId(), "The repository has no approved implementation policy.", List.of()));
        // An unanswered round-one evidence request keeps a valid open action for round two; it is
        // issued last so the targeted role has not spoken after it.
        tools.requestAdditionalEvidence(review, new DebateToolCommands.EvidenceRequest(
                metadata(review, "open-action-round-one"), RoleType.PRODUCT, RoleType.BACKEND,
                opened.topic().id(), 1, backendClaim.claimId(),
                "Provide the backend policy that covers refresh-token renewal."));
        debateService.beginSecondRound(review);

        DebateService.TurnResult evidenceRequest = tools.requestAdditionalEvidence(review,
                new DebateToolCommands.EvidenceRequest(metadata(review, "request-evidence"), RoleType.BACKEND,
                        RoleType.PRODUCT, opened.topic().id(), 2, productClaim.claimId(),
                        "Provide the signed requirement that defines refresh-token expiry."));

        assertThat(evidenceRequest.turn().turnType()).isEqualTo(DebateTurnType.EVIDENCE_REQUEST);
        assertThatThrownBy(() -> tools.submitChallenge(review, new DebateToolCommands.Challenge(
                metadata(review, "repeat-round-two"), RoleType.PRODUCT, RoleType.BACKEND, opened.topic().id(), 2,
                backendClaim.claimId(), "Provide evidence for the refresh-token implementation.", List.of(),
                "The initial requirement does not cite an implementation artifact.")))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ai.cc.chongming.review.domain.exception.ReviewErrorCode.DUPLICATE_SUBMISSION);
    }

    @Test
    void registersTopicsAcrossDistinctSubjectKeys() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        DebateTools tools = new DebateTools(
                new ClaimService(evidenceLedgerFor(store), store, new ReviewProtocolGuard()), debateService, new JudgeService(store));
        Review review = conflictDetectionReview();
        Claim productClaim = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT, "auth.policy");
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE, "mcp.security");
        store.saveClaim(productClaim);
        store.saveClaim(backendClaim);

        DebateService.RegisterTopicsResult registered = tools.registerDebateTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "open-cross"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("security-baseline",
                                List.of(productClaim.claimId(), backendClaim.claimId())))));
        DebateService.TopicResult opened = registered.topics().get(0);

        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);
        assertThat(opened.topic().claimIds()).containsExactlyInAnyOrder(productClaim.claimId(), backendClaim.claimId());
    }

    @Test
    void registersTopicWithSingleOpposingClaim() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        DebateTools tools = new DebateTools(
                new ClaimService(evidenceLedgerFor(store), store, new ReviewProtocolGuard()), debateService, new JudgeService(store));
        Review review = conflictDetectionReview();
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE, "mcp.security");
        store.saveClaim(backendClaim);

        DebateService.RegisterTopicsResult registered = tools.registerDebateTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "open-single-oppose"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("mcp.security",
                                List.of(backendClaim.claimId())))));
        DebateService.TopicResult opened = registered.topics().get(0);

        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);
        assertThat(opened.topic().claimIds()).containsExactly(backendClaim.claimId());
    }

    @Test
    void registerTopicsPersistsTheChinesePublicTitleTrimmed() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        DebateTools tools = new DebateTools(
                new ClaimService(evidenceLedgerFor(store), store, new ReviewProtocolGuard()), debateService, new JudgeService(store));
        Review review = conflictDetectionReview();
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE, "mcp.security");
        store.saveClaim(backendClaim);

        DebateService.RegisterTopicsResult registered = tools.registerDebateTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "open-with-title"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("mcp.security",
                                List.of(backendClaim.claimId()), "  MCP 安全基线未覆盖外部访问风险  "))));
        DebateService.TopicResult opened = registered.topics().get(0);

        assertThat(opened.topic().publicTitle()).isEqualTo("MCP 安全基线未覆盖外部访问风险");
        assertThat(store.findTopics(review.id()).get(0).publicTitle())
                .isEqualTo("MCP 安全基线未覆盖外部访问风险");
    }

    @Test
    void registerTopicsNormalizesBlankPublicTitleToNull() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        DebateTools tools = new DebateTools(
                new ClaimService(evidenceLedgerFor(store), store, new ReviewProtocolGuard()), debateService, new JudgeService(store));
        Review review = conflictDetectionReview();
        Claim backendClaim = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE, "mcp.security");
        store.saveClaim(backendClaim);

        DebateService.RegisterTopicsResult registered = tools.registerDebateTopics(review,
                new DebateToolCommands.RegisterTopics(metadata(review, "open-blank-title"), RoleType.DIRECTOR,
                        List.of(new DebateToolCommands.TopicProposal("mcp.security",
                                List.of(backendClaim.claimId()), "   "))));
        DebateService.TopicResult opened = registered.topics().get(0);

        assertThat(opened.topic().publicTitle()).isNull();
        assertThat(store.findTopics(review.id()).get(0).publicTitle()).isNull();
    }

    private EvidenceLedgerService evidenceLedgerFor(InMemoryReviewDebateStore store) {
        return new EvidenceLedgerService();
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
}