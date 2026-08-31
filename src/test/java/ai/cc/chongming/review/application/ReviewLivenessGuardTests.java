package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.debate.ConflictDetector;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchCommandStatus;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import ai.cc.chongming.review.domain.repository.ReviewDispatchStore;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.dispatch.InMemoryReviewDispatchStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

/**
 * [AIREVIEW-PLAN-060#5] Verifies the stage liveness heartbeat: an idle attempt is re-woken with the
 * dispatcher-consistent labels and copy, a fresh committed event resets the idle clock, terminal
 * events forget the attempt, and once the re-wake budget is exhausted each covered stage is
 * deterministically closed server-side.
 *
 * <p>[AIREVIEW-PLAN-063#1,#3] Also verifies that an idle attempt compensate-redelivers every
 * still-PENDING, unexpired dispatch envelope (DEBATE included) with the dispatcher-consistent
 * runtime/role labels, honours a per-commandId redelivery budget of {@code livenessMaxRewakes},
 * and never redelivers while the attempt is still active.
 *
 * @author wangli
 */
class ReviewLivenessGuardTests {

    private final InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
    private final AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
    private final InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
    private final ConflictDetectionService conflictDetectionService = mock(ConflictDetectionService.class);
    private final DebateService debateService = mock(DebateService.class);
    private final JudgeService judgeService = mock(JudgeService.class);
    private final ReviewCommandService commandService = mock(ReviewCommandService.class);
    private ReviewLivenessGuard guard;

    @BeforeEach
    void setUp() {
        when(adapter.send(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(adapter.deliverDispatchCommand(anyString(), anyString(), anyString(), any()))
                .thenReturn(Mono.empty());
        guard = guardWith(Duration.ZERO, 3);
    }

    // ------------------------------------------------------------------
    // INITIAL_REVIEW re-wake behaviour
    // ------------------------------------------------------------------

    @Test
    void idleInitialReviewRewakesEveryIncompleteActivatedRole() {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false),
                new RoleActivation(RoleType.BACKEND, "backend", true),
                new RoleActivation(RoleType.FRONTEND, "frontend", false));
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());

        guard.scan();

        verify(adapter, times(1)).send(eq(runtimeId), eq(runtimeId + "-product"),
                contains("初审仍未完成"));
        verify(adapter, times(1)).send(eq(runtimeId), eq(runtimeId + "-frontend"),
                contains("初审仍未完成"));
        // A completed role is never re-woken.
        verify(adapter, never()).send(eq(runtimeId), eq(runtimeId + "-backend"), anyString());
    }

    @Test
    void idleInitialReviewSkipsDirectorAndJudgeRewake() {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false),
                new RoleActivation(RoleType.JUDGE, "judge", false),
                new RoleActivation(RoleType.DIRECTOR, "director", false));
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());

        guard.scan();

        verify(adapter, times(1)).send(eq(runtimeId), eq(runtimeId + "-product"),
                contains("初审仍未完成"));
        // [AIREVIEW-PLAN-071#2] 裁决者/协调者不参与初审收尾，扫描不得误唤醒这两个标签。
        verify(adapter, never()).send(eq(runtimeId), eq(runtimeId + "-judge"), anyString());
        verify(adapter, never()).send(eq(runtimeId), eq(runtimeId + "-director"), anyString());
    }

    /**
     * [AIREVIEW-PLAN-070#3][AIREVIEW-PLAN-070#4] The INITIAL_REVIEW re-wake copy must immunise a
     * role that has already completed its initial review against re-submitting assessments/claims
     * or re-pasting conclusions: it only needs to confirm its state in one line.
     */
    @Test
    void initialReviewRewakeCarriesCompletedRoleSilenceGuidance() {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false));
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());

        guard.scan();

        verify(adapter, times(1)).send(eq(runtimeId), eq(runtimeId + "-product"),
                argThat(message -> message.contains("若你的初审已完成（initialReviewCompleted）")
                        && message.contains("不要重提交任何评估或主张")
                        && message.contains("不要重贴结论")
                        && message.contains("仅用一行确认状态")));
    }

    @Test
    void idleConflictDetectionRewakesTheDirectorWithRegisterInstructions() {
        Review review = conflictReview();
        guard.onCommitted(event(review, ReviewEventType.INITIAL_REVIEW_COMPLETED, ReviewStage.CONFLICT_DETECTION));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());

        guard.scan();

        verify(adapter, times(1)).send(eq(runtimeId), eq(runtimeId + "-director"),
                contains("register_topics"));
        verify(adapter, never()).send(eq(runtimeId), eq(runtimeId + "-judge"), anyString());
    }

    // ------------------------------------------------------------------
    // deterministic closure once the re-wake budget is exhausted
    // ------------------------------------------------------------------

    @Test
    void conflictDetectionForceRegistersCandidatesAfterMaxRewakes() {
        Review review = conflictReview();
        ClaimId claimId = new ClaimId(UUID.randomUUID());
        ConflictDetector.ConflictCandidate candidate = new ConflictDetector.ConflictCandidate(
                "api.contract", List.of(claimId), Set.of(ConflictDetector.ConflictRule.OPPOSING_POSITION),
                95, "OPPOSING_POSITION");
        ConflictDetector.ConflictDetectionResult result =
                new ConflictDetector.ConflictDetectionResult(List.of(candidate), List.of());
        when(conflictDetectionService.detect(review))
                .thenReturn(new ConflictDetectionService.Outcome(result, List.of(), List.of()));
        guard.onCommitted(event(review, ReviewEventType.INITIAL_REVIEW_COMPLETED, ReviewStage.CONFLICT_DETECTION));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());

        guard.scan();
        guard.scan();
        guard.scan();
        guard.scan();

        verify(adapter, times(4)).send(eq(runtimeId), eq(runtimeId + "-director"), anyString());
        verify(debateService, times(1)).registerTopics(eq(review), argThat(command -> {
            if (!command.metadata().idempotencyKey().value()
                    .equals("liveness-register:" + review.id().value())) {
                return false;
            }
            if (command.proposals().size() != 1) {
                return false;
            }
            DebateToolCommands.TopicProposal proposal = command.proposals().getFirst();
            return "api.contract".equals(proposal.subjectKey())
                    && proposal.claimIds().equals(List.of(claimId))
                    && proposal.publicTitle() != null
                    && proposal.publicTitle().startsWith("活性收口议题：");
        }));
        verify(debateService, never()).skipDebateWhenNoConflicts(any());
    }

    @Test
    void conflictDetectionForceSkipsDebateWhenNoCandidatesAfterMaxRewakes() {
        Review review = conflictReview();
        ConflictDetector.ConflictDetectionResult empty =
                new ConflictDetector.ConflictDetectionResult(List.of(), List.of());
        when(conflictDetectionService.detect(review))
                .thenReturn(new ConflictDetectionService.Outcome(empty, List.of(), List.of()));
        guard.onCommitted(event(review, ReviewEventType.INITIAL_REVIEW_COMPLETED, ReviewStage.CONFLICT_DETECTION));

        guard.scan();
        guard.scan();
        guard.scan();
        guard.scan();

        verify(debateService, times(1)).skipDebateWhenNoConflicts(review);
        verify(debateService, never()).registerTopics(any(), any());
    }

    @Test
    void judgingForceDraftsGateAfterMaxRewakes() {
        Review review = review(ReviewStage.JUDGING);
        guard.onCommitted(event(review, ReviewEventType.JUDGING_STARTED, ReviewStage.JUDGING));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());

        guard.scan();
        guard.scan();
        guard.scan();
        guard.scan();

        verify(adapter, times(4)).send(eq(runtimeId), eq(runtimeId + "-judge"),
                contains("draft_gate exactly once"));
        verify(judgeService, times(1)).draftGate(review);
    }

    @Test
    void initialReviewForceFailsAfterMaxRewakes() {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false),
                new RoleActivation(RoleType.BACKEND, "backend", false));
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));

        guard.scan();
        guard.scan();
        guard.scan();
        guard.scan();

        verify(commandService, times(1)).failReview(eq(review), argThat(reason -> reason != null
                && reason.startsWith("LIVENESS_TIMEOUT: 初审活性超时，未完成角色=[")
                && reason.contains("BACKEND") && reason.contains("PRODUCT")));
    }

    // ------------------------------------------------------------------
    // [AIREVIEW-PLAN-063#1] PENDING envelope compensation redelivery
    // ------------------------------------------------------------------

    @Test
    void idleDebateRedeliversPendingEnvelope() {
        Review review = debateReview();
        InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
        ReviewDispatchCommand command = pendingDispatchCommand(review, RoleType.PRODUCT);
        dispatchStore.save(command);
        guard = guardWithStore(Duration.ZERO, 3, dispatchStore);
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        String recipient = runtimeId + "-product";

        guard.scan();

        // The envelope is re-injected with the dispatcher-consistent runtime id and role label and
        // carries the exact persisted command; DEBATE is outside the rewake-covered stages, so no
        // plain send happens.
        verify(adapter, times(1)).deliverDispatchCommand(eq(runtimeId), eq(recipient),
                eq(ReviewDispatchService.envelopeText(command)), eq(command));
        verify(adapter, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void redeliverySkippedWhenRecipientActiveSinceIssuance() {
        Review review = debateReview();
        InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
        ReviewDispatchCommand command = pendingDispatchCommand(review, RoleType.PRODUCT);
        dispatchStore.save(command);
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        // [AIREVIEW-PLAN-075#2] 接收方 runtime 从命令签发之后一直有 trace 活动：重投被跳过，
        // 不会向一个仍活跃的接收方重复塞同一条信封。
        ReviewRuntimeTraceRegistry traceRegistry = mock(ReviewRuntimeTraceRegistry.class);
        when(traceRegistry.lastObservedAt(runtimeId)).thenReturn(Optional.of(command.createdAt().plusSeconds(30)));
        guard = guardWithStoreAndTrace(Duration.ZERO, 3, dispatchStore, traceRegistry);
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));

        guard.scan();

        verify(adapter, never()).deliverDispatchCommand(anyString(), anyString(), anyString(), any());
    }

    @Test
    void redeliveryCapRespected() {
        Review review = debateReview();
        InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
        ReviewDispatchCommand command = pendingDispatchCommand(review, RoleType.PRODUCT);
        dispatchStore.save(command);
        guard = guardWithStore(Duration.ZERO, 3, dispatchStore);
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        String recipient = runtimeId + "-product";

        guard.scan();
        guard.scan();
        guard.scan();
        guard.scan();

        // The per-commandId budget of livenessMaxRewakes=3 is honoured: the 4th scan stops
        // redelivering while the envelope itself stays PENDING.
        verify(adapter, times(3)).deliverDispatchCommand(eq(runtimeId), eq(recipient),
                anyString(), eq(command));
    }

    @Test
    void activeAttemptDoesNotRedeliver() {
        Review review = debateReview();
        InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
        ReviewDispatchCommand command = pendingDispatchCommand(review, RoleType.PRODUCT);
        dispatchStore.save(command);
        guard = guardWithStore(Duration.ofMinutes(10), 3, dispatchStore);
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));

        guard.scan();

        // A fresh lastActivityAt keeps the attempt below the idle threshold, so no envelope is
        // redelivered at all.
        verify(adapter, never()).deliverDispatchCommand(anyString(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------
    // [AIREVIEW-PLAN-076#3] DEBATE 焦点议题静默关题
    // ------------------------------------------------------------------

    @Test
    void quiescentFocusTopicAutoClosesResolved() throws Exception {
        Review review = debateReview();
        Claim support = claim(review, RoleType.PRODUCT, ClaimSeverity.P1, ClaimPosition.SUPPORT);
        Claim oppose = claim(review, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        DebateTurn challenge = new DebateTurn(new TurnId(UUID.randomUUID()), new TopicId(UUID.randomUUID()), 1,
                RoleType.BACKEND, RoleType.PRODUCT, DebateTurnType.CHALLENGE, support.claimId(), null,
                "后端质疑", List.of(), null, null, Instant.now());
        DebateTurn rebuttal = new DebateTurn(new TurnId(UUID.randomUUID()), challenge.topicId(), 1,
                RoleType.PRODUCT, RoleType.BACKEND, DebateTurnType.REBUTTAL, support.claimId(),
                challenge.turnId(), "产品答辩", List.of(), null, null, Instant.now());
        DebateTopic topic = DebateTopic.restore(challenge.topicId(), review.id(), "api.contract",
                List.of(support.claimId(), oppose.claimId()), DebateTopicStatus.REBUTTED, 1,
                List.of(challenge, rebuttal), null, null);
        debateStore.saveTopic(topic);
        debateStore.saveTurn(review.id(), challenge);
        debateStore.saveTurn(review.id(), rebuttal);
        guard = guardWithDebateStore(Duration.ZERO, 3, new InMemoryReviewDispatchStore(), debateStore);
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));
        ageDebateSignals(guard, review.id(), review.attemptNo(), Instant.EPOCH);

        guard.scan();

        verify(debateService, times(1)).closeTopic(eq(review), argThat(command ->
                command.topicId().equals(topic.id())
                        && command.status() == DebateTopicStatus.RESOLVED
                        && command.metadata().idempotencyKey().value()
                                .equals("liveness-close:" + topic.id().value() + ":1")));
        verify(debateService, never()).beginJudging(any());
    }

    @Test
    void quiescentWithoutTurnsEscalates() throws Exception {
        Review review = debateReview();
        Claim oppose = claim(review, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), review.id(), "api.contract",
                List.of(oppose.claimId()));
        debateStore.saveTopic(topic);
        guard = guardWithDebateStore(Duration.ZERO, 3, new InMemoryReviewDispatchStore(), debateStore);
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));
        ageDebateSignals(guard, review.id(), review.attemptNo(), Instant.EPOCH);

        guard.scan();

        verify(debateService, times(1)).closeTopic(eq(review), argThat(command ->
                command.topicId().equals(topic.id())
                        && command.status() == DebateTopicStatus.ESCALATED
                        && command.metadata().idempotencyKey().value()
                                .equals("liveness-close:" + topic.id().value() + ":1")));
        verify(debateService, never()).beginJudging(any());
    }

    @Test
    void allTerminalAutoBeginsJudging() throws Exception {
        Review review = debateReview();
        Claim support = claim(review, RoleType.PRODUCT, ClaimSeverity.P1, ClaimPosition.SUPPORT);
        Claim oppose = claim(review, RoleType.BACKEND, ClaimSeverity.P1, ClaimPosition.OPPOSE);
        DebateTurn challenge = new DebateTurn(new TurnId(UUID.randomUUID()), new TopicId(UUID.randomUUID()), 1,
                RoleType.BACKEND, RoleType.PRODUCT, DebateTurnType.CHALLENGE, support.claimId(), null,
                "后端质疑", List.of(), null, null, Instant.now());
        DebateTurn rebuttal = new DebateTurn(new TurnId(UUID.randomUUID()), challenge.topicId(), 1,
                RoleType.PRODUCT, RoleType.BACKEND, DebateTurnType.REBUTTAL, support.claimId(),
                challenge.turnId(), "产品答辩", List.of(), null, null, Instant.now());
        DebateTopic topic = DebateTopic.restore(challenge.topicId(), review.id(), "api.contract",
                List.of(support.claimId(), oppose.claimId()), DebateTopicStatus.REBUTTED, 1,
                List.of(challenge, rebuttal), null, null);
        debateStore.saveTopic(topic);
        debateStore.saveTurn(review.id(), challenge);
        debateStore.saveTurn(review.id(), rebuttal);
        // closeTopic mock 同步把持久化议题改为终态，模拟真实 DebateService 的收敛副作用。
        when(debateService.closeTopic(eq(review), any())).thenAnswer(invocation -> {
            DebateToolCommands.CloseTopic command = invocation.getArgument(1);
            DebateTopic stored = debateStore.findTopic(review.id(), command.topicId()).orElseThrow();
            stored.close(new ai.cc.chongming.review.domain.protocol.DebateStateMachine(),
                    command.status(), command.publicResolution(), Instant.now());
            debateStore.saveTopic(stored);
            return new DebateService.TopicResult(stored, false);
        });
        guard = guardWithDebateStore(Duration.ZERO, 3, new InMemoryReviewDispatchStore(), debateStore);
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));
        ageDebateSignals(guard, review.id(), review.attemptNo(), Instant.EPOCH);

        guard.scan();

        verify(debateService, times(1)).closeTopic(eq(review), any());
        verify(debateService, times(1)).beginJudging(review);
    }

    // ------------------------------------------------------------------
    // [AIREVIEW-PLAN-072#2] runtime activity participates in the idle decision
    // ------------------------------------------------------------------

    @Test
    void runtimeActivitySuppressesRewakeAndForceFail() throws Exception {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        ReviewRuntimeTraceRegistry traceRegistry = mock(ReviewRuntimeTraceRegistry.class);
        // [AIREVIEW-PLAN-072#4] 领域心跳已停摆，但运行时 trace 仍新鲜：一次扫描既不重唤醒也不強杀。
        when(traceRegistry.lastObservedAt(runtimeId)).thenReturn(Optional.of(Instant.now()));
        guard = guardWithTrace(Duration.ofMinutes(10), 3, traceRegistry);
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));
        ageState(guard, review.id(), review.attemptNo(), Instant.EPOCH);

        guard.scan();

        verify(adapter, never()).send(anyString(), anyString(), anyString());
        verify(commandService, never()).failReview(any(), anyString());
    }

    @Test
    void dualSilenceStillRewakesAndFails() throws Exception {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        ReviewRuntimeTraceRegistry traceRegistry = mock(ReviewRuntimeTraceRegistry.class);
        when(traceRegistry.lastObservedAt(runtimeId)).thenReturn(Optional.empty());
        guard = guardWithTrace(Duration.ZERO, 3, traceRegistry);
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));
        ageState(guard, review.id(), review.attemptNo(), Instant.EPOCH);

        guard.scan();
        guard.scan();
        guard.scan();
        guard.scan();

        verify(adapter, times(4)).send(eq(runtimeId), eq(runtimeId + "-product"), anyString());
        verify(commandService, times(1)).failReview(eq(review), argThat(reason -> reason != null
                && reason.startsWith("LIVENESS_TIMEOUT: 初审活性超时，未完成角色=[")
                && reason.contains("PRODUCT")));
    }

    // ------------------------------------------------------------------
    // heartbeat reset and terminal cleanup
    // ------------------------------------------------------------------

    @Test
    void newCommittedActivityResetsTheIdleClock() throws Exception {
        guard = guardWith(Duration.ofMillis(100), 3);
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false));
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());

        Thread.sleep(150);
        guard.scan();
        verify(adapter, times(1)).send(eq(runtimeId), eq(runtimeId + "-product"), anyString());

        // A fresh committed event in the same stage restarts the idle clock: an immediate scan
        // must not re-wake (nor burn another re-wake budget slot).
        guard.onCommitted(event(review, ReviewEventType.CLAIM_SUBMITTED, ReviewStage.INITIAL_REVIEW));
        guard.scan();
        verify(adapter, times(1)).send(eq(runtimeId), eq(runtimeId + "-product"), anyString());
        verify(commandService, never()).failReview(any(), anyString());
    }

    @Test
    void terminalEventClearsTheTrackedAttempt() {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false));
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));

        guard.onCommitted(event(review, ReviewEventType.REVIEW_FAILED, ReviewStage.FAILED));
        guard.scan();

        verify(adapter, never()).send(anyString(), anyString(), anyString());
        verify(commandService, never()).failReview(any(), anyString());
    }

    // ------------------------------------------------------------------
    // [AIREVIEW-PLAN-069#5] success-path terminal cleanup + redelivery eviction
    // ------------------------------------------------------------------

    @Test
    void completedStageEventClearsTheTrackedAttempt() {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false));
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));

        // No REVIEW_COMPLETED event type exists: the success-path terminal fact is a
        // COMPLETED-stage event (e.g. NOTIFICATION_SENT) and must forget the attempt too.
        guard.onCommitted(new ReviewEvent(UUID.randomUUID(), 1L, review.id(), review.attemptNo(),
                ReviewEventType.NOTIFICATION_SENT, ReviewEventType.NOTIFICATION_SENT.category(),
                ReviewStage.COMPLETED, null, null, null, null, null, null, 100,
                Instant.now(), 1, Map.of()));
        guard.scan();

        verify(adapter, never()).send(anyString(), anyString(), anyString());
        verify(commandService, never()).failReview(any(), anyString());
    }

    @Test
    void notifyingStageEventClearsTheTrackedAttempt() {
        Review review = review(ReviewStage.INITIAL_REVIEW,
                new RoleActivation(RoleType.PRODUCT, "product", false));
        guard.onCommitted(event(review, ReviewEventType.ROLE_STARTED, ReviewStage.INITIAL_REVIEW));

        guard.onCommitted(new ReviewEvent(UUID.randomUUID(), 1L, review.id(), review.attemptNo(),
                ReviewEventType.NOTIFICATION_QUEUED, ReviewEventType.NOTIFICATION_QUEUED.category(),
                ReviewStage.NOTIFYING, null, null, null, null, null, null, 96,
                Instant.now(), 1, Map.of()));
        guard.scan();

        verify(adapter, never()).send(anyString(), anyString(), anyString());
        verify(commandService, never()).failReview(any(), anyString());
    }

    @Test
    void terminalEventClearsRedeliveryCounters() throws Exception {
        Review review = debateReview();
        InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
        ReviewDispatchCommand command = pendingDispatchCommand(review, RoleType.PRODUCT);
        dispatchStore.save(command);
        guard = guardWithStore(Duration.ZERO, 3, dispatchStore);
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));
        guard.scan();
        assertThat(redeliveriesOf(guard)).hasSize(1);

        guard.onCommitted(new ReviewEvent(UUID.randomUUID(), 1L, review.id(), review.attemptNo(),
                ReviewEventType.REVIEW_CANCELLED, ReviewEventType.REVIEW_CANCELLED.category(),
                ReviewStage.CANCELLED, null, null, null, null, null, null, null,
                Instant.now(), 1, Map.of()));

        assertThat(redeliveriesOf(guard)).isEmpty();
    }

    @Test
    void scanClearsRedeliveriesBeyondTheTrackedCap() throws Exception {
        Review review = debateReview();
        guard = guardWithStore(Duration.ZERO, 3, new InMemoryReviewDispatchStore());
        guard.onCommitted(event(review, ReviewEventType.DEBATE_TOPIC_OPENED, ReviewStage.DEBATE));
        Map<String, AtomicInteger> redeliveries = redeliveriesOf(guard);
        for (int i = 0; i < 10_001; i++) {
            redeliveries.put(UUID.randomUUID().toString(), new AtomicInteger(1));
        }

        guard.scan();

        assertThat(redeliveriesOf(guard)).isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** All four core roles completed so a real protocol guard would let debate start. */
    private Review conflictReview() {
        return review(ReviewStage.CONFLICT_DETECTION,
                new RoleActivation(RoleType.PRODUCT, "product", true),
                new RoleActivation(RoleType.PROJECT, "project", true),
                new RoleActivation(RoleType.FRONTEND, "frontend", true),
                new RoleActivation(RoleType.BACKEND, "backend", true));
    }

    /** [AIREVIEW-PLAN-063#3] DEBATE 阶段评审（未覆盖阶段，验证补偿重投独立于 rewake 覆盖集合）。 */
    private Review debateReview() {
        return review(ReviewStage.DEBATE,
                new RoleActivation(RoleType.PRODUCT, "product", true),
                new RoleActivation(RoleType.BACKEND, "backend", true));
    }

    /** [AIREVIEW-PLAN-076#3] 新建一条已持久化到 debateStore 的 Claim，供静默关题用例组装议题。 */
    private Claim claim(Review owner, RoleType role, ClaimSeverity severity, ClaimPosition position) {
        Claim created = new Claim(new ClaimId(UUID.randomUUID()), owner.id(), role,
                "api.contract", severity, position, "命题-" + role, "理由-" + role, List.of());
        debateStore.saveClaim(created);
        return created;
    }

    /** [AIREVIEW-PLAN-063#3] 直接构造一条 PENDING、未过期的调度信封（参照 ReviewDispatchServiceTests 手法）。 */
    private ReviewDispatchCommand pendingDispatchCommand(Review review, RoleType recipientRole) {
        return new ReviewDispatchCommand(
                new CommandId(UUID.randomUUID()),
                review.id(),
                review.attemptNo(),
                ReviewStage.DEBATE,
                1,
                recipientRole,
                DispatchedAction.CHALLENGE,
                new TopicId(UUID.randomUUID()),
                new ClaimId(UUID.randomUUID()),
                null,
                Instant.now().plusSeconds(600),
                DispatchCommandStatus.PENDING,
                new IdempotencyKey("liveness-redelivery:" + UUID.randomUUID()),
                Instant.now());
    }

    private Review review(ReviewStage stage, RoleActivation... activations) {
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), stage, 1, 0,
                List.of(activations), Map.of());
        registry.register(review);
        return review;
    }

    private ReviewEvent event(Review review, ReviewEventType type, ReviewStage stage) {
        return new ReviewEvent(UUID.randomUUID(), 1L, review.id(), review.attemptNo(), type,
                type.category(), stage, null, null, null, null, null, null, null,
                Instant.now(), 1, Map.of());
    }

    /** [AIREVIEW-PLAN-069#5] Reads the package-private compensation-redelivery counters for assertions. */
    @SuppressWarnings("unchecked")
    private static Map<String, AtomicInteger> redeliveriesOf(ReviewLivenessGuard guard) throws Exception {
        java.lang.reflect.Field field = ReviewLivenessGuard.class.getDeclaredField("redeliveries");
        field.setAccessible(true);
        return (Map<String, AtomicInteger>) field.get(guard);
    }

    private ReviewLivenessGuard guardWith(Duration idle, int maxRewakes) {
        return guardWithStore(idle, maxRewakes, null);
    }

    /** [AIREVIEW-PLAN-063#3] dispatch store 为 null 时走旧构造，补偿重投静默关闭。 */
    private ReviewLivenessGuard guardWithStore(Duration idle, int maxRewakes, ReviewDispatchStore dispatchStore) {
        return new ReviewLivenessGuard(registry, properties(idle, maxRewakes),
                providerOf(adapter), providerOf(conflictDetectionService),
                providerOf(debateService), providerOf(judgeService), providerOf(commandService),
                dispatchStore == null ? null : providerOf(dispatchStore));
    }

    /** [AIREVIEW-PLAN-072#4] 注入运行时 trace probe；traceRegistry 为 null 时按旧 9 参构造关闭。 */
    private ReviewLivenessGuard guardWithTrace(Duration idle, int maxRewakes, ReviewRuntimeTraceRegistry traceRegistry) {
        return new ReviewLivenessGuard(registry, properties(idle, maxRewakes),
                providerOf(adapter), providerOf(conflictDetectionService),
                providerOf(debateService), providerOf(judgeService), providerOf(commandService),
                null, traceRegistry == null ? null : providerOf(traceRegistry));
    }

    /** [AIREVIEW-PLAN-075#2] 同时注入 dispatch store 与 trace probe，供重投跳过用例使用。 */
    private ReviewLivenessGuard guardWithStoreAndTrace(Duration idle, int maxRewakes,
            ReviewDispatchStore dispatchStore, ReviewRuntimeTraceRegistry traceRegistry) {
        return new ReviewLivenessGuard(registry, properties(idle, maxRewakes),
                providerOf(adapter), providerOf(conflictDetectionService),
                providerOf(debateService), providerOf(judgeService), providerOf(commandService),
                dispatchStore == null ? null : providerOf(dispatchStore),
                traceRegistry == null ? null : providerOf(traceRegistry));
    }

    /** [AIREVIEW-PLAN-076#3] 注入 dispatch store 与 debate store 供 DEBATE 静默关题用例使用。 */
    private ReviewLivenessGuard guardWithDebateStore(Duration idle, int maxRewakes,
            ReviewDispatchStore dispatchStore, InMemoryReviewDebateStore debateStore) {
        return new ReviewLivenessGuard(registry, properties(idle, maxRewakes),
                providerOf(adapter), providerOf(conflictDetectionService),
                providerOf(debateService), providerOf(judgeService), providerOf(commandService),
                dispatchStore == null ? null : providerOf(dispatchStore),
                null,
                providerOf(debateStore));
    }

    /** [AIREVIEW-PLAN-072#4] 用反射把领域心跳拨回过去，跳过 Thread.sleep 的时序不确定性。 */
    @SuppressWarnings("unchecked")
    private static void ageState(ReviewLivenessGuard guard, ReviewId reviewId, int attemptNo, Instant at)
            throws Exception {
        java.lang.reflect.Field statesField = ReviewLivenessGuard.class.getDeclaredField("states");
        statesField.setAccessible(true);
        Map<String, Object> states = (Map<String, Object>) statesField.get(guard);
        Object state = states.get(reviewId.value() + ":" + attemptNo);
        java.lang.reflect.Field lastActivityField = state.getClass().getDeclaredField("lastActivityAt");
        lastActivityField.setAccessible(true);
        lastActivityField.set(state, at);
    }

    /** [AIREVIEW-PLAN-076#3] 用反射把辩论静默关题观察窗（回合/信封双时间）拨回过去。 */
    @SuppressWarnings("unchecked")
    private static void ageDebateSignals(ReviewLivenessGuard guard, ReviewId reviewId, int attemptNo, Instant at)
            throws Exception {
        java.lang.reflect.Field statesField = ReviewLivenessGuard.class.getDeclaredField("states");
        statesField.setAccessible(true);
        Map<String, Object> states = (Map<String, Object>) statesField.get(guard);
        Object state = states.get(reviewId.value() + ":" + attemptNo);
        java.lang.reflect.Field turnField = state.getClass().getDeclaredField("lastDebateTurnAt");
        turnField.setAccessible(true);
        turnField.set(state, at);
        java.lang.reflect.Field envelopeField = state.getClass().getDeclaredField("lastEnvelopeAt");
        envelopeField.setAccessible(true);
        envelopeField.set(state, at);
    }

    private AgentScopeProperties properties(Duration idle, int maxRewakes) {
        return new AgentScopeProperties(false, "state", 48, 12, 16, Duration.ofSeconds(150), 24,
                Duration.ofMinutes(20), Duration.ofMinutes(6), true, idle, maxRewakes);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
