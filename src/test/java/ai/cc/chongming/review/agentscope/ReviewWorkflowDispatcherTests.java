package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.application.ReviewDispatchService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchCommandStatus;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkflowDispatcher;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.dispatch.InMemoryReviewDispatchStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-024#方案3] Verifies the dispatcher no longer broadcasts debate prompts: it wakes
 * the Director, issues the server-side rebuttal envelope after a committed challenge, and injects
 * validated envelopes only into the recipient role's context.
 *
 * @author wangli
 */
class ReviewWorkflowDispatcherTests {

    private final InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
    private final InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
    private final InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
    private final List<ai.cc.chongming.review.domain.event.ReviewEventDraft> published = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<ReviewWorkflowDispatcher> dispatcherRef = new AtomicReference<>();
    // Mirrors ReviewEventService: every committed dispatch lifecycle fact wakes the dispatcher.
    private final ReviewDispatchService dispatchService = new ReviewDispatchService(
            dispatchStore, debateStore, draft -> {
                published.add(draft);
                ReviewWorkflowDispatcher current = dispatcherRef.get();
                if (current != null) {
                    current.onCommitted(ReviewEvent.committed(sequence.incrementAndGet(), draft));
                }
            });
    private final AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
    private ReviewWorkflowDispatcher dispatcher;

    private Review review;
    private String runtimeId;
    private DebateTopic topic;
    private DebateTopic openTopic;
    private Claim claim;
    private DebateTurn challenge;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<AgentRuntimeAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        dispatcher = new ReviewWorkflowDispatcher(provider, registry, dispatchService, debateStore);
        dispatcherRef.set(dispatcher);
        when(adapter.send(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(adapter.deliverDispatchCommand(anyString(), anyString(), anyString(), any())).thenReturn(Mono.empty());
        when(adapter.stopRoleRuns(anyString())).thenReturn(Mono.empty());

        review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.DEBATE_ROUND_1, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", true),
                        new RoleActivation(RoleType.BACKEND, "backend", true)),
                Map.of());
        registry.register(review);
        runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());

        claim = new Claim(new ClaimId(UUID.randomUUID()), review.id(), RoleType.BACKEND,
                "api.contract", ClaimSeverity.P1, ClaimPosition.OPPOSE, "接口契约与需求冲突", "字段定义不一致", List.of());
        debateStore.saveClaim(claim);
        challenge = new DebateTurn(new TurnId(UUID.randomUUID()), new TopicId(UUID.randomUUID()), 1,
                RoleType.PRODUCT, RoleType.BACKEND, DebateTurnType.CHALLENGE, claim.claimId(), null,
                "质疑接口契约", List.of(), null, null, Instant.now());
        topic = DebateTopic.restore(challenge.topicId(), review.id(), "api.contract",
                List.of(claim.claimId()), DebateTopicStatus.CHALLENGED, 1, List.of(challenge), null, null);
        debateStore.saveTopic(topic);
        debateStore.saveTurn(review.id(), challenge);
        // An OPEN topic so director-issued CHALLENGE commands stay applicable in lifecycle tests.
        openTopic = new DebateTopic(new TopicId(UUID.randomUUID()), review.id(), "api.contract",
                List.of(claim.claimId()));
        debateStore.saveTopic(openTopic);
    }

    @Test
    void challengeCommitIssuesOneRebuttalEnvelopeAddressedOnlyToTheChallengedRole() {
        dispatcher.onCommitted(event(ReviewEventType.CHALLENGE_SUBMITTED, challenge.turnId(), Map.of()));

        List<ReviewDispatchCommand> commands = dispatchStore.findByReview(review.id(), review.attemptNo());
        assertThat(commands).hasSize(1);
        ReviewDispatchCommand command = commands.getFirst();
        assertThat(command.allowedAction()).isEqualTo(DispatchedAction.REBUTTAL);
        assertThat(command.recipientRole()).isEqualTo(RoleType.BACKEND);
        assertThat(command.status()).isEqualTo(DispatchCommandStatus.PENDING);
        assertThat(command.targetTurnId()).isEqualTo(challenge.turnId());

        verify(adapter, timeout(3000)).deliverDispatchCommand(
                eq(runtimeId), eq(runtimeId + "-backend"), contains("allowedAction=REBUTTAL"), eq(command));
        // Only the Director wake goes through plain send; no role receives a broadcast prompt.
        verify(adapter, timeout(3000)).send(eq(runtimeId), eq(runtimeId + "-director"), anyString());
        verify(adapter, never()).send(eq(runtimeId), argThat(label -> roleLabel(label)), anyString());
    }

    @Test
    void replayingTheSameChallengeEventDoesNotDuplicateTheRebuttalEnvelope() {
        dispatcher.onCommitted(event(ReviewEventType.CHALLENGE_SUBMITTED, challenge.turnId(), Map.of()));
        dispatcher.onCommitted(event(ReviewEventType.CHALLENGE_SUBMITTED, challenge.turnId(), Map.of()));

        assertThat(dispatchStore.findByReview(review.id(), review.attemptNo())).hasSize(1);
    }

    @Test
    void openedTopicWakesOnlyTheDirectorWithoutBroadcastingToRoles() {
        dispatcher.onCommitted(event(ReviewEventType.DEBATE_TOPIC_OPENED, null, Map.of()));

        verify(adapter, timeout(3000)).send(eq(runtimeId), eq(runtimeId + "-director"),
                contains("dispatch_debate_action"));
        verify(adapter, never()).send(eq(runtimeId), argThat(this::roleLabel), anyString());
        verify(adapter, never()).deliverDispatchCommand(anyString(), anyString(), anyString(), any());
    }

    @Test
    void issuedDispatchEventDeliversTheEnvelopeOnlyToTheRecipientRole() {
        ReviewDispatchCommand command = dispatchService.issue(review, new ReviewDispatchService.DispatchProposal(
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("director-call")),
                RoleType.PRODUCT, DispatchedAction.CHALLENGE, 1,
                openTopic.id(), claim.claimId(), null, Instant.now().plusSeconds(600),
                RoleType.DIRECTOR, "DIRECTOR")).command();

        // The committed DISPATCH_COMMAND_ISSUED fact is forwarded to the dispatcher by the
        // publisher wired above, exactly like ReviewEventService does in production.
        verify(adapter, timeout(3000)).deliverDispatchCommand(
                eq(runtimeId), eq(runtimeId + "-product"), contains("allowedAction=CHALLENGE"), eq(command));
        verify(adapter, never()).deliverDispatchCommand(
                eq(runtimeId), eq(runtimeId + "-backend"), anyString(), any());
    }

    @Test
    void judgingStartRejectsPendingCommandsAndStopsRoleRuns() {
        ReviewDispatchCommand command = dispatchService.issue(review, new ReviewDispatchService.DispatchProposal(
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("director-call-2")),
                RoleType.PRODUCT, DispatchedAction.CHALLENGE, 1,
                openTopic.id(), claim.claimId(), null, Instant.now().plusSeconds(600),
                RoleType.DIRECTOR, "DIRECTOR")).command();

        dispatcher.onCommitted(event(ReviewEventType.JUDGING_STARTED, null, Map.of()));

        assertThat(dispatchStore.findById(review.id(), command.commandId()))
                .get().extracting(ReviewDispatchCommand::status).isEqualTo(DispatchCommandStatus.REJECTED);
        verify(adapter, timeout(3000)).stopRoleRuns(runtimeId);
    }

    /**
     * [AIREVIEW-PLAN-024#方案4 收口] Every path into judging must wake the Judge exactly once:
     * the forced-convergence guard only transitions the stage, so the dispatcher owns the wake.
     */
    @Test
    void judgingStartedAndDebateSkippedEachWakeTheJudgeExactlyOnce() {
        dispatcher.onCommitted(event(ReviewEventType.JUDGING_STARTED, null, Map.of()));
        verify(adapter, timeout(3000)).send(eq(runtimeId), eq(runtimeId + "-judge"),
                contains("draft_gate exactly once"));

        dispatcher.onCommitted(event(ReviewEventType.DEBATE_SKIPPED, null, Map.of()));
        verify(adapter, timeout(3000).times(2)).send(eq(runtimeId), eq(runtimeId + "-judge"), anyString());
    }

    @Test
    void cancellationRejectsPendingCommands() {
        ReviewDispatchCommand command = dispatchService.issue(review, new ReviewDispatchService.DispatchProposal(
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("director-call-3")),
                RoleType.PRODUCT, DispatchedAction.CHALLENGE, 1,
                openTopic.id(), claim.claimId(), null, Instant.now().plusSeconds(600),
                RoleType.DIRECTOR, "DIRECTOR")).command();

        dispatcher.onCommitted(event(ReviewEventType.REVIEW_CANCELLED, null, Map.of()));

        assertThat(dispatchStore.findById(review.id(), command.commandId()))
                .get().extracting(ReviewDispatchCommand::status).isEqualTo(DispatchCommandStatus.REJECTED);
    }

    @Test
    void droppedCommandWakesTheDirectorWithAReissueHint() {
        dispatcher.onCommitted(event(ReviewEventType.DISPATCH_COMMAND_EXPIRED, null,
                Map.of("commandId", UUID.randomUUID().toString(), "allowedAction", "CHALLENGE",
                        "recipientRole", "PRODUCT", "reason", "EXPIRED_BEFORE_USE")));

        verify(adapter, timeout(3000)).send(eq(runtimeId), eq(runtimeId + "-director"),
                contains("Reissue a valid dispatch_debate_action command"));
    }

    // --- [AIREVIEW-PLAN-046#1] server-side challenge dispatch -------------------------------

    @Test
    void openedTopicWithBothSidesDispatchesOneChallengePerOpposeRoleAgainstHighestSeveritySupport() {
        Review opposing = freshReview(
                RoleType.PRODUCT, RoleType.ARCHITECTURE, RoleType.BACKEND, RoleType.FRONTEND);
        Claim supportLower = claim(opposing, RoleType.PRODUCT, ClaimSeverity.P2, ClaimPosition.SUPPORT);
        Claim supportHighest = claim(opposing, RoleType.ARCHITECTURE, ClaimSeverity.P0, ClaimPosition.SUPPORT);
        Claim opposeBackend = claim(opposing, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        Claim opposeFrontend = claim(opposing, RoleType.FRONTEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        DebateTopic bothSides = new DebateTopic(new TopicId(UUID.randomUUID()), opposing.id(), "api.contract",
                List.of(supportLower.claimId(), supportHighest.claimId(),
                        opposeBackend.claimId(), opposeFrontend.claimId()));
        debateStore.saveTopic(bothSides);

        dispatcher.onCommitted(openedEvent(opposing, bothSides));

        List<ReviewDispatchCommand> commands =
                dispatchStore.findByReview(opposing.id(), opposing.attemptNo());
        assertThat(commands).hasSize(2);
        assertThat(commands).allSatisfy(command -> {
            assertThat(command.allowedAction()).isEqualTo(DispatchedAction.CHALLENGE);
            assertThat(command.round()).isEqualTo(1);
            assertThat(command.topicId()).isEqualTo(bothSides.id());
            // Highest severity wins over mount order: the P0 SUPPORT is targeted, not the earlier P2.
            assertThat(command.targetClaimId()).isEqualTo(supportHighest.claimId());
            assertThat(command.idempotencyKey().value())
                    .startsWith("dispatch:challenge:" + bothSides.id().value() + ":");
        });
        assertThat(commands).extracting(ReviewDispatchCommand::recipientRole)
                .containsExactlyInAnyOrder(RoleType.BACKEND, RoleType.FRONTEND);

        // Each objector receives exactly its own envelope; the support side is never challenged.
        String opposingRuntimeId = ReviewRuntimeContext.runtimeIdFor(opposing.id(), opposing.attemptNo());
        verify(adapter, timeout(3000)).deliverDispatchCommand(
                eq(opposingRuntimeId), eq(opposingRuntimeId + "-backend"),
                contains("allowedAction=CHALLENGE"), any());
        verify(adapter, timeout(3000)).deliverDispatchCommand(
                eq(opposingRuntimeId), eq(opposingRuntimeId + "-frontend"),
                contains("allowedAction=CHALLENGE"), any());
        verify(adapter, never()).deliverDispatchCommand(
                eq(opposingRuntimeId), eq(opposingRuntimeId + "-product"), anyString(), any());
        verify(adapter, never()).deliverDispatchCommand(
                eq(opposingRuntimeId), eq(opposingRuntimeId + "-architecture"), anyString(), any());
    }

    @Test
    void repeatedOpenTriggerDoesNotStackChallengeEnvelopes() {
        Review opposing = freshReview(RoleType.PRODUCT, RoleType.BACKEND);
        Claim support = claim(opposing, RoleType.PRODUCT, ClaimSeverity.P1, ClaimPosition.SUPPORT);
        Claim oppose = claim(opposing, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        DebateTopic bothSides = new DebateTopic(new TopicId(UUID.randomUUID()), opposing.id(), "api.contract",
                List.of(support.claimId(), oppose.claimId()));
        debateStore.saveTopic(bothSides);
        ReviewEvent opened = openedEvent(opposing, bothSides);

        dispatcher.onCommitted(opened);
        dispatcher.onCommitted(opened);

        // The idempotency key dispatch:challenge:{topicId}:{recipientRole} lets only one envelope stand.
        List<ReviewDispatchCommand> commands =
                dispatchStore.findByReview(opposing.id(), opposing.attemptNo());
        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().recipientRole()).isEqualTo(RoleType.BACKEND);
    }

    @Test
    void oppositionOnlyTopicOpensWithoutDispatchThenDefenseSupportClaimDispatchesChallenges() {
        Review defending = freshReview(RoleType.PRODUCT, RoleType.BACKEND, RoleType.FRONTEND);
        Claim opposeBackend = claim(defending, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        Claim opposeFrontend = claim(defending, RoleType.FRONTEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        DebateTopic objectorOnly = new DebateTopic(new TopicId(UUID.randomUUID()), defending.id(), "api.contract",
                List.of(opposeBackend.claimId(), opposeFrontend.claimId()));
        debateStore.saveTopic(objectorOnly);

        // 异议答辩议题开题（仅 OPPOSE）: opening alone must not dispatch anything.
        dispatcher.onCommitted(openedEvent(defending, objectorOnly));
        assertThat(dispatchStore.findByReview(defending.id(), defending.attemptNo())).isEmpty();

        // The defender's SUPPORT claim lands on the topic and completes both sides.
        Claim defense = claim(defending, RoleType.PRODUCT, ClaimSeverity.P1, ClaimPosition.SUPPORT);
        objectorOnly.attachClaim(defense.claimId());
        dispatcher.onCommitted(claimEvent(defending, ReviewStage.DEBATE_ROUND_1, defense.claimId()));

        List<ReviewDispatchCommand> commands =
                dispatchStore.findByReview(defending.id(), defending.attemptNo());
        assertThat(commands).hasSize(2);
        assertThat(commands).allSatisfy(command -> {
            assertThat(command.allowedAction()).isEqualTo(DispatchedAction.CHALLENGE);
            assertThat(command.targetClaimId()).isEqualTo(defense.claimId());
            assertThat(command.round()).isEqualTo(1);
        });
        assertThat(commands).extracting(ReviewDispatchCommand::recipientRole)
                .containsExactlyInAnyOrder(RoleType.BACKEND, RoleType.FRONTEND);
        assertThat(published)
                .filteredOn(draft -> draft.type() == ReviewEventType.DISPATCH_COMMAND_ISSUED)
                .extracting(ReviewEventDraft::payload)
                .allSatisfy(payload -> assertThat(payload.get("reason"))
                        .isEqualTo("SERVER_CHALLENGE_AFTER_DEFENSE"));
    }

    @Test
    void topicAlreadyHavingAChallengeTurnIsSkipped() {
        Review manual = freshReview(RoleType.PRODUCT, RoleType.BACKEND);
        Claim support = claim(manual, RoleType.PRODUCT, ClaimSeverity.P1, ClaimPosition.SUPPORT);
        Claim oppose = claim(manual, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), manual.id(), "api.contract",
                List.of(support.claimId(), oppose.claimId()));
        debateStore.saveTopic(topic);
        DebateTurn committedChallenge = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), 1,
                RoleType.BACKEND, RoleType.PRODUCT, DebateTurnType.CHALLENGE, support.claimId(), null,
                "协调者手动派发的质询", List.of(), null, null, Instant.now());
        // Compatible with a coordinator-driven flow: a committed CHALLENGE turn means the server must
        // not re-arm automatic envelopes (the next server envelope is the rebuttal).
        debateStore.saveTurn(manual.id(), committedChallenge);

        dispatcher.onCommitted(openedEvent(manual, topic));

        assertThat(dispatchStore.findByReview(manual.id(), manual.attemptNo())).isEmpty();
    }

    @Test
    void challengeDispatchIsSkippedOutsideDebateRounds() {
        Review judging = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.JUDGING, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", true),
                        new RoleActivation(RoleType.BACKEND, "backend", true)),
                Map.of());
        registry.register(judging);
        Claim support = claim(judging, RoleType.PRODUCT, ClaimSeverity.P1, ClaimPosition.SUPPORT);
        Claim oppose = claim(judging, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), judging.id(), "api.contract",
                List.of(support.claimId(), oppose.claimId()));
        debateStore.saveTopic(topic);

        dispatcher.onCommitted(openedEvent(judging, topic));

        assertThat(dispatchStore.findByReview(judging.id(), judging.attemptNo())).isEmpty();
    }

    @Test
    void secondSupportClaimDoesNotReArmServerChallenges() {
        Review defending = freshReview(RoleType.PRODUCT, RoleType.BACKEND, RoleType.FRONTEND);
        Claim opposeBackend = claim(defending, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        Claim opposeFrontend = claim(defending, RoleType.FRONTEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        Claim support = claim(defending, RoleType.PRODUCT, ClaimSeverity.P1, ClaimPosition.SUPPORT);
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), defending.id(), "api.contract",
                List.of(opposeBackend.claimId(), opposeFrontend.claimId()));
        debateStore.saveTopic(topic);
        topic.attachClaim(support.claimId());

        dispatcher.onCommitted(claimEvent(defending, ReviewStage.DEBATE_ROUND_1, support.claimId()));
        List<ReviewDispatchCommand> first =
                dispatchStore.findByReview(defending.id(), defending.attemptNo());
        assertThat(first).hasSize(2);

        // A later SUPPORT claim on an already two-sided topic must not stack (nor re-arm) envelopes.
        Claim laterSupport = claim(defending, RoleType.PRODUCT, ClaimSeverity.P2, ClaimPosition.SUPPORT);
        topic.attachClaim(laterSupport.claimId());
        dispatcher.onCommitted(claimEvent(defending, ReviewStage.DEBATE_ROUND_1, laterSupport.claimId()));

        assertThat(dispatchStore.findByReview(defending.id(), defending.attemptNo())).hasSize(2);
    }

    // --- helpers -------------------------------------------------------------

    private Review freshReview(RoleType... roles) {
        Review fresh = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.DEBATE_ROUND_1, 1, 0,
                Arrays.stream(roles)
                        .map(role -> new RoleActivation(
                                role, role.name().toLowerCase(java.util.Locale.ROOT), true))
                        .toList(),
                Map.of());
        registry.register(fresh);
        return fresh;
    }

    private Claim claim(Review owner, RoleType role, ClaimSeverity severity, ClaimPosition position) {
        Claim created = new Claim(new ClaimId(UUID.randomUUID()), owner.id(), role,
                "api.contract", severity, position, "命题-" + role, "理由-" + role, List.of());
        debateStore.saveClaim(created);
        return created;
    }

    private ReviewEvent openedEvent(Review review, DebateTopic topic) {
        return new ReviewEvent(UUID.randomUUID(), 1L, review.id(), review.attemptNo(),
                ReviewEventType.DEBATE_TOPIC_OPENED, ReviewEventType.DEBATE_TOPIC_OPENED.category(),
                review.stage(), null, null, topic.id(), null, null, null, null,
                Instant.now(), 1, Map.of());
    }

    private ReviewEvent claimEvent(Review review, ReviewStage stage, ClaimId claimId) {
        return new ReviewEvent(UUID.randomUUID(), 1L, review.id(), review.attemptNo(),
                ReviewEventType.CLAIM_SUBMITTED, ReviewEventType.CLAIM_SUBMITTED.category(),
                stage, null, null, null, claimId, null, null, 40,
                Instant.now(), 1, Map.of("subjectKey", "api.contract", "severity", "P1"));
    }

    private boolean roleLabel(String label) {
        return label != null && (label.endsWith("-product") || label.endsWith("-backend")
                || label.endsWith("-frontend") || label.endsWith("-project"));
    }

    private ReviewEvent event(ReviewEventType type, TurnId turnId, Map<String, String> payload) {
        return new ReviewEvent(UUID.randomUUID(), 1L, review.id(), review.attemptNo(), type, type.category(),
                review.stage(), null, null, topic.id(), claim.claimId(), turnId, 1, null,
                Instant.now(), 1, payload);
    }
}
