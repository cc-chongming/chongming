package ai.cc.chongming.review.application;

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
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * @author wangli
 */
class ReviewLivenessGuardTests {

    private final InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
    private final AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
    private final ConflictDetectionService conflictDetectionService = mock(ConflictDetectionService.class);
    private final DebateService debateService = mock(DebateService.class);
    private final JudgeService judgeService = mock(JudgeService.class);
    private final ReviewCommandService commandService = mock(ReviewCommandService.class);
    private ReviewLivenessGuard guard;

    @BeforeEach
    void setUp() {
        when(adapter.send(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
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

    private ReviewLivenessGuard guardWith(Duration idle, int maxRewakes) {
        return new ReviewLivenessGuard(registry, properties(idle, maxRewakes),
                providerOf(adapter), providerOf(conflictDetectionService),
                providerOf(debateService), providerOf(judgeService), providerOf(commandService));
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
