package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.agentscope.AgentEventAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeRoleRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeStartRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentScopeReviewRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewDirectorHarnessFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.infrastructure.agentscope.RoleSubagentFactory;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import io.agentscope.core.permission.PermissionMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a review has one active director and cancellation releases the next-attempt lock.
 *
 * @author wangli
 */
class AgentScopeReviewRuntimeAdapterTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsSecondDirectorThenAllowsNewAttemptAfterCancellation() {
        ReviewRuntimeContext firstAttempt = context(1);
        AgentScopeReviewRuntimeAdapter adapter = adapter();
        AgentRuntimeStartRequest firstStart = startRequest(firstAttempt);

        adapter.start(firstStart).block();

        assertThatThrownBy(() -> adapter.start(firstStart).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active director runtime");

        adapter.cancel(firstAttempt.runtimeId()).block();
        assertThatThrownBy(() -> adapter.resume(firstAttempt.runtimeId()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled runtime cannot be resumed");

        ReviewRuntimeContext secondAttempt = new ReviewRuntimeContext(
                firstAttempt.reviewId(), 2, firstAttempt.userId(), "trace-002", IntakeCancellation.neverCancelled());
        adapter.start(startRequest(secondAttempt)).block();
    }

    @Test
    void registersRoleWhenAdapterReplacesOnlyTheCancellationSignal() {
        ReviewRuntimeContext context = context(1);
        AgentScopeReviewRuntimeAdapter adapter = adapter();
        adapter.start(startRequest(context)).block();

        adapter.registerRole(new AgentRuntimeRoleRequest(
                context.runtimeId(),
                context,
                RoleType.PRODUCT,
                context.roleLabel(RoleType.PRODUCT),
                context.roleSessionId(RoleType.PRODUCT))).block();
    }

    @Test
    void rejectsRoleRegistrationWithoutRuntimeContext() {
        ReviewRuntimeContext context = context(1);
        AgentScopeReviewRuntimeAdapter adapter = adapter();
        adapter.start(startRequest(context)).block();

        assertThatThrownBy(() -> adapter.registerRole(new AgentRuntimeRoleRequest(
                context.runtimeId(), null, RoleType.PRODUCT, context.roleLabel(RoleType.PRODUCT),
                context.roleSessionId(RoleType.PRODUCT))).block())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("runtimeContext must not be null");
    }

    @Test
    void createsDirectorWithBypassPermissionForAutonomousPlanApproval() {
        ReviewRuntimeContext context = context(1);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                new ReviewProperties(temporaryDirectory.toString(), 8, 2),
                new com.fasterxml.jackson.databind.ObjectMapper());
        AgentScopeProperties agentScopeProperties = new AgentScopeProperties(
                false, temporaryDirectory.resolve("state").toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<io.agentscope.harness.agent.DistributedStore> storeProvider = Mockito.mock(ObjectProvider.class);
        ModelGateway gateway = (request, cancellation) -> Mono.just(new ModelGateway.ModelResponse(
                "response-001", "test-model", "plan", new ModelGateway.Usage(1, 1, 2),
                ModelGateway.FinishReason.STOP, Duration.ofMillis(5), 1, request.traceId()));
        ReviewDirectorHarnessFactory factory = new ReviewDirectorHarnessFactory(
                workspaceLayout, gateway, agentScopeProperties, storeProvider);

        ReviewDirectorHarnessFactory.DirectorRuntime director = factory.create(context);

        try {
            assertThat(director.agent().getAgentState().getPermissionContext().getMode())
                    .isEqualTo(PermissionMode.BYPASS);
        } finally {
            director.agent().close();
        }
    }

    @Test
    void directorDoesNotReceiveGenericWorkspaceContextAndKeepsPlanTools() {
        ReviewRuntimeContext context = context(1);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                new ReviewProperties(temporaryDirectory.toString(), 8, 2),
                new com.fasterxml.jackson.databind.ObjectMapper());
        AgentScopeProperties properties = new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString());
        AtomicReference<ModelGateway.ModelRequest> captured = new AtomicReference<>();
        ModelGateway gateway = (request, cancellation) -> {
            captured.set(request);
            return Mono.just(new ModelGateway.ModelResponse(
                    "response-001", "test-model", "plan", new ModelGateway.Usage(1, 1, 2),
                    ModelGateway.FinishReason.STOP, Duration.ofMillis(5), 1, request.traceId()));
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<io.agentscope.harness.agent.DistributedStore> storeProvider = Mockito.mock(ObjectProvider.class);
        ReviewDirectorHarnessFactory.DirectorRuntime director = new ReviewDirectorHarnessFactory(
                workspaceLayout, gateway, properties, storeProvider).create(context);

        try {
            director.agent().streamEvents("Create the total review plan.").blockLast();
        } finally {
            director.agent().close();
        }

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().publicContext())
                .doesNotContain("AgentStateStore", "Domain Knowledge", "Memory Recall", "Workspace Context");
        assertThat(captured.get().allowedTools())
                .contains("plan_enter", "plan_write", "plan_exit", "todo_write", "read_file", "list_files", "write_file");
    }

    @Test
    void failsReviewWhenRoleStreamEndsWithoutCompleteInitialReview() {
        ReviewRuntimeContext context = context(1);
        Review review = Review.restore(context.reviewId(), ReviewStage.INITIAL_REVIEW, context.attemptNo(), 0,
                List.of(new RoleActivation(RoleType.PRODUCT, context.roleLabel(RoleType.PRODUCT), false)),
                java.util.Map.of());
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        List<ReviewEventDraft> events = new ArrayList<>();
        InitialReviewProgressService progressService = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add);
        AgentScopeReviewRuntimeAdapter adapter = adapter(registry, progressService);

        adapter.start(startRequest(context)).block();
        adapter.registerRole(new AgentRuntimeRoleRequest(
                context.runtimeId(), context, RoleType.PRODUCT, context.roleLabel(RoleType.PRODUCT),
                context.roleSessionId(RoleType.PRODUCT))).block();

        assertThatThrownBy(() -> adapter.send(context.runtimeId(), context.roleLabel(RoleType.PRODUCT),
                "Perform the assigned review role.").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROLE_INCOMPLETE");
        assertThat(review.stage()).isEqualTo(ReviewStage.FAILED);
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.ROLE_FAILED, ReviewEventType.REVIEW_FAILED);
    }

    private AgentScopeReviewRuntimeAdapter adapter() {
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                reviewProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        AgentScopeProperties agentScopeProperties = new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString());
        ModelGateway gateway = (request, cancellation) -> Mono.just(new ModelGateway.ModelResponse(
                "response-001", "test-model", "Review plan prepared.", new ModelGateway.Usage(1, 1, 2),
                ModelGateway.FinishReason.STOP, Duration.ofMillis(5), 1, request.traceId()));
        @SuppressWarnings("unchecked")
        ObjectProvider<io.agentscope.harness.agent.DistributedStore> storeProvider = Mockito.mock(ObjectProvider.class);
        return new AgentScopeReviewRuntimeAdapter(
                new ReviewDirectorHarnessFactory(workspaceLayout, gateway, agentScopeProperties, storeProvider),
                new RoleSubagentFactory(
                        new ai.cc.chongming.review.domain.role.RolePackRegistry(
                                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()),
                        gateway,
                        agentScopeProperties,
                        workspaceLayout),
                new AgentEventAdapter());
    }

    private AgentScopeReviewRuntimeAdapter adapter(
            InMemoryReviewRegistry registry, InitialReviewProgressService progressService) {
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                reviewProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        AgentScopeProperties properties = new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString());
        ModelGateway gateway = (request, cancellation) -> Mono.just(new ModelGateway.ModelResponse(
                "response-001", "test-model", "Review completed without a tool call.",
                new ModelGateway.Usage(1, 1, 2), ModelGateway.FinishReason.STOP,
                Duration.ofMillis(5), 1, request.traceId()));
        @SuppressWarnings("unchecked")
        ObjectProvider<io.agentscope.harness.agent.DistributedStore> storeProvider = Mockito.mock(ObjectProvider.class);
        return new AgentScopeReviewRuntimeAdapter(
                new ReviewDirectorHarnessFactory(workspaceLayout, gateway, properties, storeProvider),
                new RoleSubagentFactory(
                        new ai.cc.chongming.review.domain.role.RolePackRegistry(
                                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()),
                        gateway,
                        properties,
                        workspaceLayout),
                new AgentEventAdapter(), registry, progressService);
    }

    private AgentRuntimeStartRequest startRequest(ReviewRuntimeContext context) {
        return new AgentRuntimeStartRequest(
                context.runtimeId(), context.userId(), context.directorSessionId(), "Create the total review plan.", context);
    }

    private ReviewRuntimeContext context(int attemptNo) {
        return new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), attemptNo, "user-001", "trace-001", IntakeCancellation.neverCancelled());
    }
}
