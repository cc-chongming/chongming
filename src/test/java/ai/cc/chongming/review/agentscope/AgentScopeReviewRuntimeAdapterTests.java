package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.agentscope.AgentEventAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeRoleRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeSession;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeStartRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentScopeReviewRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.ContextScoutHarnessFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewDirectorHarnessFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.infrastructure.agentscope.RoleSubagentFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ScoutToolTraceCollector;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

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
    void directorNativeFilesystemIsRootedAtTheAttemptWorkspace() throws Exception {
        ReviewRuntimeContext context = context(1);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                new ReviewProperties(temporaryDirectory.toString(), 8, 2),
                new com.fasterxml.jackson.databind.ObjectMapper());
        ReviewWorkspaceLayout.ReviewWorkspace workspace = workspaceLayout.open(context);
        Files.createDirectories(workspace.attempt().resolve("input"));
        Files.writeString(workspace.attempt().resolve("input/requirement.md"), "评审需求");
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<ModelGateway.ModelRequest> afterListing = new AtomicReference<>();
        ModelGateway gateway = (request, cancellation) -> {
            if (invocations.incrementAndGet() == 1) {
                return Mono.just(new ModelGateway.ModelResponse(
                        "response-001", "test-model", "", new ModelGateway.Usage(1, 1, 2),
                        ModelGateway.FinishReason.TOOL_CALL, Duration.ofMillis(5), 1,
                        List.of(new ModelGateway.ToolCall("call-list-root", "list_files", Map.of("path", "."))),
                        request.traceId()));
            }
            afterListing.set(request);
            return Mono.just(new ModelGateway.ModelResponse(
                    "response-002", "test-model", "工作区已确认。", new ModelGateway.Usage(1, 1, 2),
                    ModelGateway.FinishReason.STOP, Duration.ofMillis(5), 1, request.traceId()));
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<io.agentscope.harness.agent.DistributedStore> storeProvider = Mockito.mock(ObjectProvider.class);
        ReviewDirectorHarnessFactory.DirectorRuntime director = new ReviewDirectorHarnessFactory(
                workspaceLayout,
                gateway,
                new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString()),
                storeProvider).create(context);

        try {
            director.agent().streamEvents("确认工作区。").blockLast();
        } finally {
            director.agent().close();
        }

        assertThat(afterListing.get()).isNotNull();
        assertThat(afterListing.get().publicContext()).contains("[DIR]  /input");
        assertThat(afterListing.get().publicContext()).doesNotContain("/AGENTS.md", "/pom.xml");
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

    @Test
    void failsReviewWhenDirectorEndsConflictDetectionWithoutTransition() {
        ReviewRuntimeContext context = context(1);
        Review review = Review.restore(context.reviewId(), ReviewStage.CONFLICT_DETECTION, context.attemptNo(), 0,
                List.of(), Map.of());
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        List<ReviewEventDraft> events = new ArrayList<>();
        AgentScopeReviewRuntimeAdapter adapter = adapter(registry, null, events);

        adapter.start(startRequest(context)).block();

        assertThatThrownBy(() -> adapter.send(context.runtimeId(), context.directorLabel(),
                "Persisted Claim analysis is complete.").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIRECTOR_INCOMPLETE");

        assertThat(review.stage()).isEqualTo(ReviewStage.FAILED);
        assertThat(events).extracting(ReviewEventDraft::type).containsExactly(ReviewEventType.REVIEW_FAILED);
        assertThat(events.getFirst().payload()).containsEntry("failureType", "DIRECTOR_CONFLICT_INCOMPLETE");
    }

    @Test
    void degradesScoutFailureAndDefersDirectorUntilWorkflowDispatch() {
        ReviewRuntimeContext context = context(1);
        Review review = Review.restore(context.reviewId(), ReviewStage.PLANNING, context.attemptNo(), 0,
                List.of(), java.util.Map.of());
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        List<ReviewEventDraft> events = new ArrayList<>();
        HarnessAgent scout = Mockito.mock(HarnessAgent.class);
        ContextScoutHarnessFactory scoutFactory = Mockito.mock(ContextScoutHarnessFactory.class);
        Mockito.when(scoutFactory.createRuntime(Mockito.any(), Mockito.any()))
                .thenReturn(new ContextScoutHarnessFactory.ScoutRuntime(
                        scout, new ScoutToolTraceCollector()));
        Mockito.when(scout.streamEvents(Mockito.anyString(), Mockito.any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.error(new ModelGatewayException(
                        ModelGatewayException.Code.MODEL_CALL_TIMEOUT, "token=must-not-reach-public-events")));
        AtomicInteger directorModelCalls = new AtomicInteger();
        AgentScopeReviewRuntimeAdapter adapter = adapter(registry, scoutFactory, events, directorModelCalls);

        AgentRuntimeSession session = adapter.start(startRequest(context)).block();

        assertThat(session.runtimeId()).isEqualTo(context.runtimeId());
        assertThat(review.stage()).isEqualTo(ReviewStage.PLANNING);
        assertThat(directorModelCalls).hasValue(0);
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.CONTEXT_SCOUT_DEGRADED);
        assertThat(events.getFirst().payload())
                .containsEntry("status", "DEGRADED")
                .containsEntry("reasonCode", "MODEL_CALL_TIMEOUT")
                .containsEntry("publicSummary", "Context Scout 模型调用超时，已跳过项目上下文预处理，Director 将继续评审。");
        assertThat(events.getFirst().payload().toString()).doesNotContain("token=must-not-reach-public-events");
        Mockito.verify(scout).close();

        adapter.send(context.runtimeId(), context.directorLabel(),
                "All core initial reviews are complete. First call list_persisted_claims.").block();

        assertThat(directorModelCalls).hasValue(1);
    }

    @Test
    void degradesScoutConstructionFailureAndKeepsDirectorIdle() {
        ReviewRuntimeContext context = context(1);
        Review review = Review.restore(context.reviewId(), ReviewStage.PLANNING, context.attemptNo(), 0,
                List.of(), java.util.Map.of());
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        List<ReviewEventDraft> events = new ArrayList<>();
        ContextScoutHarnessFactory scoutFactory = Mockito.mock(ContextScoutHarnessFactory.class);
        Mockito.when(scoutFactory.createRuntime(Mockito.any(), Mockito.any())).thenThrow(new ModelGatewayException(
                ModelGatewayException.Code.MODEL_NETWORK_ERROR, "unavailable before stream subscription"));
        AtomicInteger directorModelCalls = new AtomicInteger();
        AgentScopeReviewRuntimeAdapter adapter = adapter(registry, scoutFactory, events, directorModelCalls);

        adapter.start(startRequest(context)).block();

        assertThat(review.stage()).isEqualTo(ReviewStage.PLANNING);
        assertThat(directorModelCalls.get()).isZero();
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.CONTEXT_SCOUT_DEGRADED);
        assertThat(events.getFirst().payload())
                .containsEntry("reasonCode", "MODEL_NETWORK_ERROR")
                .containsEntry("publicSummary", "Context Scout 模型服务暂不可用，已跳过项目上下文预处理，Director 将继续评审。");
    }

    @Test
    void degradesScoutWhenItViolatesTheInitRetrievalContract() {
        ReviewRuntimeContext context = context(1);
        Review review = Review.restore(context.reviewId(), ReviewStage.PLANNING, context.attemptNo(), 0,
                List.of(), Map.of());
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        List<ReviewEventDraft> events = new ArrayList<>();
        HarnessAgent scout = Mockito.mock(HarnessAgent.class);
        ContextScoutHarnessFactory scoutFactory = Mockito.mock(ContextScoutHarnessFactory.class);
        Mockito.when(scoutFactory.createRuntime(Mockito.any(), Mockito.any()))
                .thenReturn(new ContextScoutHarnessFactory.ScoutRuntime(
                        scout, new ScoutToolTraceCollector()));
        Mockito.when(scout.streamEvents(Mockito.anyString(), Mockito.any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.range(1, 3)
                        .map(index -> new ToolCallStartEvent("reply-" + index, "call-" + index, "glob_files")));
        AtomicInteger directorModelCalls = new AtomicInteger();
        AgentScopeReviewRuntimeAdapter adapter = adapter(registry, scoutFactory, events, directorModelCalls);

        adapter.start(startRequest(context)).block();

        assertThat(directorModelCalls).hasValue(0);
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.CONTEXT_SCOUT_DEGRADED);
        assertThat(events.getFirst().payload())
                .containsEntry("reasonCode", "CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED");
    }

    @Test
    void doesNotRunDirectorWhenTheAttemptIsAlreadyCancelledBeforeScoutStarts() {
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "user-001", "trace-001", () -> true);
        Review review = Review.restore(context.reviewId(), ReviewStage.PLANNING, context.attemptNo(), 0,
                List.of(), java.util.Map.of());
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        ContextScoutHarnessFactory scoutFactory = Mockito.mock(ContextScoutHarnessFactory.class);
        AtomicInteger directorModelCalls = new AtomicInteger();
        AgentScopeReviewRuntimeAdapter adapter = adapter(registry, scoutFactory, new ArrayList<>(), directorModelCalls);

        assertThatThrownBy(() -> adapter.start(startRequest(context)).block())
                .isInstanceOf(ai.cc.chongming.review.application.ReviewIntakeException.class)
                .hasMessageContaining("cancelled");

        assertThat(directorModelCalls.get()).isZero();
        Mockito.verifyNoInteractions(scoutFactory);
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

    private AgentScopeReviewRuntimeAdapter adapter(
            InMemoryReviewRegistry registry,
            InitialReviewProgressService progressService,
            List<ReviewEventDraft> events) {
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
                new AgentEventAdapter(),
                registry,
                progressService,
                null,
                null,
                null,
                events::add);
    }

    private AgentScopeReviewRuntimeAdapter adapter(
            InMemoryReviewRegistry registry,
            ContextScoutHarnessFactory scoutFactory,
            List<ReviewEventDraft> events,
            AtomicInteger directorModelCalls) {
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                reviewProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        AgentScopeProperties properties = new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString());
        ModelGateway gateway = (request, cancellation) -> {
            directorModelCalls.incrementAndGet();
            return Mono.just(new ModelGateway.ModelResponse(
                    "response-001", "test-model", "Director continued after Scout degradation.",
                    new ModelGateway.Usage(1, 1, 2), ModelGateway.FinishReason.STOP,
                    Duration.ofMillis(5), 1, request.traceId()));
        };
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
                new AgentEventAdapter(),
                registry,
                null,
                null,
                null,
                scoutFactory,
                events::add);
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
