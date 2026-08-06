package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewOrchestrationService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.RoleActivationService;
import ai.cc.chongming.review.config.ReviewOrchestrationProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeEvent;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeEventType;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeRoleRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeSession;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeStartRequest;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.support.FakeAgentRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies director total planning, mandatory-role launch, bounded revisions and cancellation.
 * [AIREVIEW-PLAN-020#0.5.1] Also verifies that role rounds are dispatched in parallel only after
 * every role (including the pre-registered Judge) has been registered and applied in order.
 *
 * @author wangli
 */
class ReviewOrchestrationServiceTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsCoreRolesRecordsPlanRevisionAndCancelsAttempt() {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        ReviewOrchestrationService service =
                buildService(new FakeAgentRuntimeAdapter(), stateMachine, new ReviewOrchestrationProperties(3, 4));
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                new ReviewProperties(temporaryDirectory.toString(), 8, 2), new ObjectMapper());
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                review.id(), review.attemptNo(), "user-001", "trace-001", IntakeCancellation.neverCancelled());

        ReviewOrchestrationService.StartResult result = startReview(service, review, context);

        assertThat(result.coreActivations()).hasSize(5);
        assertThat(review.stage()).isEqualTo(ReviewStage.INITIAL_REVIEW);
        assertThat(review.roleActivations()).hasSize(5);
        assertThat(service.revisePlan(context, workspaceLayout.open(context), List.of("Resolve conflict"), "Conflict found")
                .plan().planVersion()).isEqualTo(2);

        service.cancel(review, context).block();
        assertThat(review.stage()).isEqualTo(ReviewStage.CANCELLED);
        assertThat(service.events(context)).extracting(ReviewOrchestrationService.OrchestrationEvent::type)
                .contains(ReviewOrchestrationService.OrchestrationEventType.PLAN_CREATED,
                        ReviewOrchestrationService.OrchestrationEventType.PLAN_REVISED,
                        ReviewOrchestrationService.OrchestrationEventType.CANCELLED);
    }

    /**
     * [AIREVIEW-PLAN-020#0.5.1] Registration/application of every mandatory role (PRODUCT, PROJECT,
     * FRONTEND, BACKEND and the pre-registered JUDGE) must finish before any role round message is
     * dispatched. The serial legacy implementation fails this because it alternates
     * register-then-send per role.
     */
    @Test
    void registersAllRolesBeforeDispatchingAnyRound() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter();
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        ReviewOrchestrationService service =
                buildService(adapter, stateMachine, new ReviewOrchestrationProperties(3, 4));
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                review.id(), review.attemptNo(), "user-001", "trace-001", IntakeCancellation.neverCancelled());

        startReview(service, review, context);

        List<AgentRuntimeEventType> types = adapter.streamEvents(context.runtimeId())
                .take(11)
                .collectList()
                .block(Duration.ofSeconds(10))
                .stream().map(AgentRuntimeEvent::type).toList();
        assertThat(types).startsWith(AgentRuntimeEventType.STARTED);
        assertThat(types).containsSubsequence(AgentRuntimeEventType.ROLE_REGISTERED,
                AgentRuntimeEventType.ROLE_REGISTERED, AgentRuntimeEventType.ROLE_REGISTERED,
                AgentRuntimeEventType.ROLE_REGISTERED, AgentRuntimeEventType.ROLE_REGISTERED);
        assertThat(types.indexOf(AgentRuntimeEventType.MESSAGE_SENT))
                .isGreaterThan(types.lastIndexOf(AgentRuntimeEventType.ROLE_REGISTERED));
        assertThat(types).filteredOn(type -> type == AgentRuntimeEventType.MESSAGE_SENT).hasSize(5);
    }

    /**
     * [AIREVIEW-PLAN-020#0.5.1] Role rounds must overlap after registration completes, so the
     * concurrent gateway load stays bounded by the configured parallelism instead of one at a time.
     */
    @Test
    void dispatchesRoleRoundsInParallel() {
        ParallelProbeAdapter adapter = new ParallelProbeAdapter();
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        ReviewOrchestrationService service =
                buildService(adapter, stateMachine, new ReviewOrchestrationProperties(3, 4));
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                review.id(), review.attemptNo(), "user-001", "trace-001", IntakeCancellation.neverCancelled());

        startReview(service, review, context);

        assertThat(adapter.maxConcurrentSends()).isGreaterThan(1);
    }

    /**
     * [AIREVIEW-PLAN-020#0.5.1] Parallel dispatch must not reorder the public activation receipts:
     * PRODUCT, PROJECT, FRONTEND, BACKEND, then JUDGE.
     */
    @Test
    void keepsCoreActivationOrderProductProjectFrontendBackendJudge() {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        ReviewOrchestrationService service =
                buildService(new FakeAgentRuntimeAdapter(), stateMachine, new ReviewOrchestrationProperties(3, 4));
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                review.id(), review.attemptNo(), "user-001", "trace-001", IntakeCancellation.neverCancelled());

        ReviewOrchestrationService.StartResult result = startReview(service, review, context);

        assertThat(result.coreActivations())
                .extracting(receipt -> receipt.activation().roleType())
                .containsExactly(RoleType.PRODUCT, RoleType.PROJECT,
                        RoleType.FRONTEND, RoleType.BACKEND, RoleType.JUDGE);
    }

    private ReviewOrchestrationService buildService(
            AgentRuntimeAdapter adapter, ReviewStateMachine stateMachine, ReviewOrchestrationProperties properties) {
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(reviewProperties, new ObjectMapper());
        RoleActivationService activationService = new RoleActivationService(
                new ReviewProtocolGuard(), new RolePackRegistry(new PathMatchingResourcePatternResolver()), reviewProperties);
        return new ReviewOrchestrationService(adapter, workspaceLayout, activationService, stateMachine, properties);
    }

    private ReviewOrchestrationService.StartResult startReview(
            ReviewOrchestrationService service, Review review, ReviewRuntimeContext context) {
        return service.start(new ReviewOrchestrationService.StartRequest(
                review, context, List.of("Run mandatory role review"), "Initial total plan", "Begin review")).block();
    }

    /**
     * Delegates every call to the deterministic fake adapter but stalls each {@code send} for a fixed
     * delay so overlapping role rounds can be observed through the maximum concurrent in-flight count.
     *
     * @author wangli
     */
    private static final class ParallelProbeAdapter implements AgentRuntimeAdapter {

        private final FakeAgentRuntimeAdapter delegate = new FakeAgentRuntimeAdapter();
        private final AtomicInteger activeSends = new AtomicInteger();
        private final AtomicInteger maxConcurrentSends = new AtomicInteger();

        @Override
        public Mono<AgentRuntimeSession> start(AgentRuntimeStartRequest request) {
            return delegate.start(request);
        }

        @Override
        public Flux<AgentRuntimeEvent> streamEvents(String runtimeId) {
            return delegate.streamEvents(runtimeId);
        }

        @Override
        public Mono<Void> registerRole(AgentRuntimeRoleRequest request) {
            return delegate.registerRole(request);
        }

        @Override
        public Mono<Void> send(String runtimeId, String recipientLabel, String message) {
            return Mono.defer(() -> {
                int current = activeSends.incrementAndGet();
                maxConcurrentSends.accumulateAndGet(current, Math::max);
                return Mono.delay(Duration.ofMillis(50))
                        .then(delegate.send(runtimeId, recipientLabel, message))
                        .then(Mono.fromRunnable(activeSends::decrementAndGet));
            });
        }

        @Override
        public Mono<Void> cancel(String runtimeId) {
            return delegate.cancel(runtimeId);
        }

        @Override
        public Mono<Void> close(String runtimeId) {
            return delegate.close(runtimeId);
        }

        @Override
        public Mono<AgentRuntimeSession> resume(String runtimeId) {
            return delegate.resume(runtimeId);
        }

        int maxConcurrentSends() {
            return maxConcurrentSends.get();
        }
    }
}
