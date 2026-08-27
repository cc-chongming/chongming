package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.AssessmentService;
import ai.cc.chongming.review.application.ClaimService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
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
import ai.cc.chongming.review.infrastructure.agentscope.ReviewRoleToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.infrastructure.agentscope.RoleSubagentFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ScoutToolTraceCollector;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
 * [AIREVIEW-PLAN-023#5] Verifies runtime lifecycle and persisted Context Scout completion ordering.
 *
 * @author zyj
 */
class AgentScopeReviewRuntimeAdapterTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesScoutCompletionOnlyAfterTheConclusionWasPersisted() {
        ReviewRuntimeContext context = context(1);
        Review review = Review.restore(context.reviewId(), ReviewStage.PLANNING, context.attemptNo(), 0,
                List.of(), Map.of());
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        List<ReviewEventDraft> events = new ArrayList<>();
        HarnessAgent scout = Mockito.mock(HarnessAgent.class);
        ContextScoutHarnessFactory scoutFactory = Mockito.mock(ContextScoutHarnessFactory.class);
        String result = "{\"summary\":\"上下文已收集\"}";
        ContextScoutConclusion conclusion = new ContextScoutConclusion(
                context.reviewId(), 1, 1, "上下文已收集", List.of(), List.of(), List.of(), List.of(),
                List.of(), Map.of(), result, java.time.Instant.parse("2026-08-10T08:00:00Z"));
        Mockito.when(scoutFactory.createRuntime(Mockito.any(), Mockito.any()))
                .thenReturn(new ContextScoutHarnessFactory.ScoutRuntime(scout, new ScoutToolTraceCollector()));
        Mockito.when(scout.streamEvents(Mockito.anyString(), Mockito.any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent(result)
                        .build())));
        Mockito.when(scoutFactory.recordResult(Mockito.any(), Mockito.any(), Mockito.eq(result)))
                .thenReturn(conclusion);
        AgentScopeReviewRuntimeAdapter adapter = adapter(registry, scoutFactory, events, new AtomicInteger());

        adapter.start(startRequest(context)).block();

        Mockito.verify(scoutFactory).recordResult(
                Mockito.argThat(actual -> actual.reviewId().equals(context.reviewId())
                        && actual.attemptNo() == context.attemptNo()),
                Mockito.any(),
                Mockito.eq(result));
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.CONTEXT_SCOUT_COMPLETED);
        assertThat(events.getFirst().payload())
                .containsEntry("status", "COMPLETED")
                .containsEntry("schemaVersion", "1")
                .containsEntry("publicSummary", "上下文已收集")
                .containsEntry("conclusionRef", context.reviewId().value() + ":1");
        adapter.cancel(context.runtimeId()).block();
    }

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
                .thenReturn(Flux.range(1, 10)
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

    @Test
    void completesInitialReviewOnlyAfterAllRequiredAssessmentsAreSubmitted() {
        ReviewRuntimeContext context = context(1);
        // The stage transition requires every core role to have finished its initial review, so the
        // other three core roles are pre-activated as already completed while PRODUCT runs the
        // assessment submission flow under test.
        List<RoleActivation> coreActivations = new ArrayList<>();
        for (RoleType coreRole : List.of(
                RoleType.PRODUCT, RoleType.PROJECT, RoleType.FRONTEND, RoleType.BACKEND)) {
            coreActivations.add(new RoleActivation(
                    coreRole, context.roleLabel(coreRole), coreRole != RoleType.PRODUCT));
        }
        Review review = Review.restore(context.reviewId(), ReviewStage.INITIAL_REVIEW, context.attemptNo(), 0,
                coreActivations,
                Map.of());
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        List<ReviewEventDraft> events = new ArrayList<>();
        ai.cc.chongming.review.domain.role.RolePackRegistry rolePackRegistry =
                new ai.cc.chongming.review.domain.role.RolePackRegistry(
                        new org.springframework.core.io.support.PathMatchingResourcePatternResolver());
        InMemoryReviewAssessmentStore assessmentStore = new InMemoryReviewAssessmentStore();
        AssessmentService assessmentService = new AssessmentService(assessmentStore, rolePackRegistry);
        InitialReviewProgressService progressService = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add, assessmentService);
        InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
        ClaimService claimService = new ClaimService(
                Mockito.mock(EvidenceLedgerService.class), debateStore, new ReviewProtocolGuard());
        ReviewRoleToolFactory roleToolFactory = new ReviewRoleToolFactory(
                registry, claimService, progressService, assessmentService, debateStore);
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                reviewProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        AgentScopeProperties properties = new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString());
        List<String> requiredKeys = List.of("product.requirement_completeness", "product.acceptance_criteria",
                "product.user_value", "product.scope_boundary", "product.testability",
                "product.adversarial_scrutiny", "product.core_value_stance", "product.recognized_strengths");
        List<ModelGateway.ModelResponse> script = new ArrayList<>();
        // The role first tries to complete without any assessment: the coverage guard must reject it.
        script.add(toolCallResponse("call-complete-early", "complete_initial_review",
                Map.of("publicSummary", "试图用摘要绕过覆盖检查")));
        for (int index = 0; index < requiredKeys.size(); index++) {
            script.add(toolCallResponse("call-assessment-" + index, "submit_assessment", Map.of(
                    "checkpointKey", requiredKeys.get(index),
                    "status", "CONFIRMED",
                    "summary", "已确认 " + requiredKeys.get(index))));
        }
        script.add(toolCallResponse("call-complete-final", "complete_initial_review",
                Map.of("publicSummary", "PRODUCT 初审完成")));
        script.add(new ModelGateway.ModelResponse(
                "response-stop", "test-model", "初审已完成。", new ModelGateway.Usage(1, 1, 2),
                ModelGateway.FinishReason.STOP, Duration.ofMillis(5), 1, "trace-001"));
        AtomicInteger turns = new AtomicInteger();
        ModelGateway gateway = (request, cancellation) -> Mono.just(
                script.get(Math.min(turns.getAndIncrement(), script.size() - 1)));
        @SuppressWarnings("unchecked")
        ObjectProvider<io.agentscope.harness.agent.DistributedStore> storeProvider = Mockito.mock(ObjectProvider.class);
        AgentScopeReviewRuntimeAdapter adapter = new AgentScopeReviewRuntimeAdapter(
                new ReviewDirectorHarnessFactory(workspaceLayout, gateway, properties, storeProvider),
                new RoleSubagentFactory(rolePackRegistry, gateway, properties, workspaceLayout,
                        roleToolFactory, null, null, null, assessmentService),
                new AgentEventAdapter(),
                registry,
                progressService,
                null,
                null,
                null,
                events::add);

        adapter.start(startRequest(context)).block();
        adapter.registerRole(new AgentRuntimeRoleRequest(
                context.runtimeId(), context, RoleType.PRODUCT, context.roleLabel(RoleType.PRODUCT),
                context.roleSessionId(RoleType.PRODUCT))).block();

        adapter.send(context.runtimeId(), context.roleLabel(RoleType.PRODUCT), "请开始本次初审。").block();

        // The early completion was rejected; the role only completed after all required CONFIRMED
        // assessments were persisted, and the public summary was derived server-side.
        assertThat(review.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
        assertThat(review.roleActivations()).filteredOn(activation -> activation.roleType() == RoleType.PRODUCT)
                .singleElement()
                .satisfies(activation -> assertThat(activation.initialReviewCompleted()).isTrue());
        assertThat(assessmentStore.findByReview(review.id(), context.attemptNo(), RoleType.PRODUCT))
                .hasSize(requiredKeys.size())
                .allSatisfy(assessment -> assertThat(assessment.status()).isEqualTo(AssessmentStatus.CONFIRMED));
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.ROLE_COMPLETED, ReviewEventType.INITIAL_REVIEW_COMPLETED);
        assertThat(events.getFirst().payload().get("summary").toString())
                .contains("CONFIRMED=" + requiredKeys.size())
                .contains("product.user_value：CONFIRMED");
    }

    private ModelGateway.ModelResponse toolCallResponse(String callId, String toolName, Map<String, Object> input) {
        return new ModelGateway.ModelResponse(
                "response-" + callId, "test-model", "", new ModelGateway.Usage(1, 1, 2),
                ModelGateway.FinishReason.TOOL_CALL, Duration.ofMillis(5), 1,
                List.of(new ModelGateway.ToolCall(callId, toolName, input)), "trace-001");
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
