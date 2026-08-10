package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewCancellationToken;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.application.ReviewEventDrafts;
import ai.cc.chongming.review.application.ReviewEventPublisher;
import ai.cc.chongming.review.application.ReviewRuntimeTraceRegistry;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * [AIREVIEW-PLAN-010#1.6] AgentScope 2.0.0 adapter that owns Harness lifecycle while keeping project governance outside AgentScope.
 *
 * @author wangli
 */
@Component
public class AgentScopeReviewRuntimeAdapter implements AgentRuntimeAdapter {

    private static final Map<String, Integer> SCOUT_INIT_TOOL_LIMITS = Map.of(
            "glob_files", 2,
            "grep_files", 3,
            "read_file", 4);

    private final ReviewDirectorHarnessFactory directorFactory;
    private final RoleSubagentFactory roleSubagentFactory;
    private final AgentEventAdapter eventAdapter;
    private final ReviewRegistry reviewRegistry;
    private final InitialReviewProgressService initialReviewProgressService;
    private final ReviewRuntimeTraceRegistry runtimeTraceRegistry;
    private final ReviewAgUiEventMapper agUiEventMapper;
    private final ContextScoutHarnessFactory contextScoutHarnessFactory;
    private final ReviewEventPublisher eventPublisher;
    private final AgentScopeProperties agentScopeProperties;
    private final ConcurrentMap<String, RuntimeState> runtimes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeRuntimeByReview = new ConcurrentHashMap<>();
    private final Set<String> cancelledRuntimeIds = ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(AgentScopeReviewRuntimeAdapter.class);
    private JudgeService judgeService;
    private ReviewDebateStore reviewDebateStore;

    public AgentScopeReviewRuntimeAdapter(
            ReviewDirectorHarnessFactory directorFactory,
            RoleSubagentFactory roleSubagentFactory,
            AgentEventAdapter eventAdapter) {
        this(directorFactory, roleSubagentFactory, eventAdapter, null, null, null, null, null,
                ReviewEventPublisher.noop(), defaultAgentScopeProperties());
    }

    public AgentScopeReviewRuntimeAdapter(
            ReviewDirectorHarnessFactory directorFactory,
            RoleSubagentFactory roleSubagentFactory,
            AgentEventAdapter eventAdapter,
            ReviewRegistry reviewRegistry,
            InitialReviewProgressService initialReviewProgressService) {
        this(directorFactory, roleSubagentFactory, eventAdapter, reviewRegistry, initialReviewProgressService,
                null, null, null, ReviewEventPublisher.noop(), defaultAgentScopeProperties());
    }

    public AgentScopeReviewRuntimeAdapter(
            ReviewDirectorHarnessFactory directorFactory,
            RoleSubagentFactory roleSubagentFactory,
            AgentEventAdapter eventAdapter,
            ReviewRegistry reviewRegistry,
            InitialReviewProgressService initialReviewProgressService,
            ReviewRuntimeTraceRegistry runtimeTraceRegistry,
            ReviewAgUiEventMapper agUiEventMapper,
            ContextScoutHarnessFactory contextScoutHarnessFactory,
            ReviewEventPublisher eventPublisher) {
        this(directorFactory, roleSubagentFactory, eventAdapter, reviewRegistry, initialReviewProgressService,
                runtimeTraceRegistry, agUiEventMapper, contextScoutHarnessFactory, eventPublisher,
                defaultAgentScopeProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentScopeReviewRuntimeAdapter(
            ReviewDirectorHarnessFactory directorFactory,
            RoleSubagentFactory roleSubagentFactory,
            AgentEventAdapter eventAdapter,
            ReviewRegistry reviewRegistry,
            InitialReviewProgressService initialReviewProgressService,
            ReviewRuntimeTraceRegistry runtimeTraceRegistry,
            ReviewAgUiEventMapper agUiEventMapper,
            ContextScoutHarnessFactory contextScoutHarnessFactory,
            ReviewEventPublisher eventPublisher,
            AgentScopeProperties agentScopeProperties) {
        this.directorFactory = Objects.requireNonNull(directorFactory, "directorFactory must not be null");
        this.roleSubagentFactory = Objects.requireNonNull(roleSubagentFactory, "roleSubagentFactory must not be null");
        this.eventAdapter = Objects.requireNonNull(eventAdapter, "eventAdapter must not be null");
        this.reviewRegistry = reviewRegistry;
        this.initialReviewProgressService = initialReviewProgressService;
        this.runtimeTraceRegistry = runtimeTraceRegistry;
        this.agUiEventMapper = agUiEventMapper;
        this.contextScoutHarnessFactory = contextScoutHarnessFactory;
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.agentScopeProperties = Objects.requireNonNull(agentScopeProperties, "agentScopeProperties must not be null");
    }

    /**
     * Supplies the deterministic judging fallback. Optional so existing constructor call sites keep
     * working; when absent the judge agent remains the only path to a Gate draft.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureJudgeFallback(JudgeService judgeService, ReviewDebateStore reviewDebateStore) {
        this.judgeService = judgeService;
        this.reviewDebateStore = reviewDebateStore;
    }

    @Override
    public Mono<AgentRuntimeSession> start(AgentRuntimeStartRequest request) {
        return Mono.defer(() -> {
            Objects.requireNonNull(request, "request must not be null");
            ReviewRuntimeContext requestedContext = request.runtimeContext();
            if (requestedContext == null) {
                return Mono.error(new IllegalArgumentException("runtimeContext is required for AgentScope execution"));
            }
            if (!request.runtimeId().equals(requestedContext.runtimeId())
                    || !request.userId().equals(requestedContext.userId())
                    || !request.sessionId().equals(requestedContext.directorSessionId())) {
                return Mono.error(new IllegalArgumentException("runtime identity must be derived from ReviewRuntimeContext"));
            }
            ReviewCancellationToken cancellation = new ReviewCancellationToken(requestedContext.cancellation());
            ReviewRuntimeContext context = requestedContext.withCancellation(cancellation);
            String reviewKey = context.reviewId().value().toString();
            String existingRuntime = activeRuntimeByReview.putIfAbsent(reviewKey, request.runtimeId());
            if (existingRuntime != null) {
                return Mono.error(new IllegalStateException("review already has an active director runtime"));
            }
            ReviewDirectorHarnessFactory.DirectorRuntime director;
            try {
                director = directorFactory.create(context);
            } catch (RuntimeException exception) {
                activeRuntimeByReview.remove(reviewKey, request.runtimeId());
                return Mono.error(exception);
            }
            RuntimeState state = new RuntimeState(context, cancellation, director);
            if (runtimes.putIfAbsent(request.runtimeId(), state) != null) {
                activeRuntimeByReview.remove(reviewKey, request.runtimeId());
                director.agent().close();
                return Mono.error(new IllegalStateException("runtime already exists: " + request.runtimeId()));
            }
            state.emit(AgentRuntimeEventType.STARTED, context.directorLabel(), "director-created");
            Mono<Void> scout = runScout(state, context, director.workspace());
            // The director must stay idle until every core role has completed its independent review.
            // ReviewWorkflowDispatcher wakes it from the committed INITIAL_REVIEW_COMPLETED event. Starting
            // its conversational loop here would block role registration and lets it see a stale PLANNING state.
            return scout.thenReturn(new AgentRuntimeSession(request.runtimeId(), request.userId(), request.sessionId()));
        });
    }

    private Mono<Void> runScout(
            RuntimeState state, ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace) {
        if (contextScoutHarnessFactory == null) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            if (state.cancelled()) {
                return Mono.empty();
            }
            AtomicBoolean degraded = new AtomicBoolean();
            ScoutInitToolBudget nativeToolBudget = new ScoutInitToolBudget(agentScopeProperties.scoutMaxToolCalls());
            Mono<Void> execution;
            try {
                ContextScoutHarnessFactory.ScoutRuntime scoutRuntime = contextScoutHarnessFactory.createRuntime(context, workspace);
                HarnessAgent scout = scoutRuntime.agent();
                state.emit(AgentRuntimeEventType.ROLE_REGISTERED, "CONTEXT_SCOUT", "CONTEXT_SCOUT");
                execution = scout.streamEvents(
                                "请准备本次评审的公开项目上下文。",
                                agentContext(context, context.runtimeId() + ":context-scout"))
                        .doOnNext(event -> {
                            if (event instanceof ToolCallStartEvent toolCallStart) {
                                nativeToolBudget.consume(toolCallStart.getToolCallName());
                            }
                            if (event instanceof AgentResultEvent result && result.getResult() != null) {
                                String visibleResult = result.getResult().getTextContent();
                                if (visibleResult != null && !visibleResult.isBlank()) {
                                    contextScoutHarnessFactory.recordResult(context, workspace, visibleResult);
                                }
                            }
                            emitRawObservation(
                                    state, event, RoleType.DIRECTOR, "CONTEXT_SCOUT",
                                    context.runtimeId() + ":context-scout", ReviewStage.PLANNING,
                                    scoutRuntime.toolTraceCollector());
                        })
                        .timeout(agentScopeProperties.scoutTimeout())
                        .onErrorMap(TimeoutException.class,
                                ignored -> new ScoutLimitExceededException("CONTEXT_SCOUT_TIMEOUT"))
                        .then()
                        .onErrorResume(exception -> recoverScoutFailure(state, exception, degraded))
                        .doFinally(signal -> scout.close());
            } catch (RuntimeException exception) {
                execution = recoverScoutFailure(state, exception, degraded);
            }
            return execution.doOnSuccess(ignored -> {
                if (state.cancelled()) {
                    return;
                }
                state.emit(
                        degraded.get() ? AgentRuntimeEventType.DEGRADED : AgentRuntimeEventType.MESSAGE_SENT,
                        "CONTEXT_SCOUT",
                        degraded.get() ? "context-scout-degraded" : "completed");
            });
        });
    }

    private Mono<Void> recoverScoutFailure(RuntimeState state, Throwable failure, AtomicBoolean degraded) {
        if (isScoutCancellation(state, failure)) {
            return Mono.error(failure);
        }
        degraded.set(true);
        recordScoutDegradation(state, failure);
        return Mono.empty();
    }

    /**
     * A Scout failure is advisory: record a safe, replayable warning and let Director continue.
     * Cancellation is deliberately excluded because it terminates the attempt rather than degrading it.
     */
    private void recordScoutDegradation(RuntimeState state, Throwable failure) {
        String reasonCode = scoutFailureCode(failure);
        String publicSummary = scoutFailureSummary(reasonCode);
        publishLifecycle(state, RoleType.DIRECTOR, "CONTEXT_SCOUT", "DEGRADED");
        if (reviewRegistry == null) {
            return;
        }
        reviewRegistry.find(state.context().reviewId()).ifPresent(review -> {
            synchronized (review) {
                if (state.cancelled()
                        || review.attemptNo() != state.context().attemptNo()
                        || review.stage().isTerminal()) {
                    return;
                }
                eventPublisher.publish(ReviewEventDrafts.completedCommand(
                        review,
                        ReviewEventType.CONTEXT_SCOUT_DEGRADED,
                        RoleType.DIRECTOR,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of(
                                "status", "DEGRADED",
                                "reasonCode", reasonCode,
                                "publicSummary", publicSummary)));
            }
        });
    }

    private static boolean isScoutCancellation(RuntimeState state, Throwable failure) {
        if (state.cancelled()) {
            return true;
        }
        return isControlledStop(failure);
    }

    /**
     * A cooperative interruption (CancellationException or a model-cancelled signal) marks a
     * controlled stop rather than an agent failure.
     */
    private static boolean isControlledStop(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            if (current instanceof ModelGatewayException modelFailure
                    && modelFailure.code() == ModelGatewayException.Code.MODEL_CANCELLED) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String scoutFailureCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ModelGatewayException modelFailure) {
                return modelFailure.code().name();
            }
            if (current instanceof ScoutLimitExceededException scoutLimitExceeded) {
                return scoutLimitExceeded.reasonCode();
            }
            current = current.getCause();
        }
        return "CONTEXT_SCOUT_UNAVAILABLE";
    }

    private static String scoutFailureSummary(String reasonCode) {
        return switch (reasonCode) {
            case "MODEL_CALL_TIMEOUT" -> "Context Scout 模型调用超时，已跳过项目上下文预处理，Director 将继续评审。";
            case "MODEL_RATE_LIMITED" -> "Context Scout 当前受模型限流影响，已跳过项目上下文预处理，Director 将继续评审。";
            case "MODEL_REQUEST_REJECTED" -> "Context Scout 模型请求未被接受，已跳过项目上下文预处理，Director 将继续评审。";
            case "MODEL_NETWORK_ERROR", "MODEL_PROVIDER_ERROR" -> "Context Scout 模型服务暂不可用，已跳过项目上下文预处理，Director 将继续评审。";
            case "CONTEXT_SCOUT_TOOL_BUDGET_EXCEEDED" -> "Context Scout 检索范围未在预算内收敛，已跳过项目上下文预处理，Director 将继续评审。";
            case "CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED" -> "Context Scout 未遵守受限初始化检索契约，已跳过项目上下文预处理，Director 将继续评审。";
            case "CONTEXT_SCOUT_TIMEOUT" -> "Context Scout 在限定时间内未完成，已跳过项目上下文预处理，Director 将继续评审。";
            default -> "Context Scout 未能完成项目上下文预处理，Director 将继续评审。";
        };
    }

    @Override
    public Flux<AgentRuntimeEvent> streamEvents(String runtimeId) {
        return state(runtimeId).events().asFlux();
    }

    @Override
    public Mono<Void> registerRole(AgentRuntimeRoleRequest request) {
        return Mono.fromRunnable(() -> {
            Objects.requireNonNull(request, "request must not be null");
            RuntimeState state = state(request.runtimeId());
            if (!sameRuntimeIdentity(state.context(), request.runtimeContext())) {
                throw new IllegalArgumentException("role request must use the active runtime context");
            }
            state.cancellation().checkCancelled();
            RoleSubagentFactory.RoleRuntime role = roleSubagentFactory.create(
                    state.context(), state.director().workspace(), request.roleType());
            if (state.roles().putIfAbsent(request.label(), role) != null) {
                role.agent().close();
                throw new IllegalStateException("role runtime already exists: " + request.label());
            }
            state.emit(AgentRuntimeEventType.ROLE_REGISTERED, request.label(), request.roleType().name());
        });
    }

    @Override
    public Mono<Void> send(String runtimeId, String recipientLabel, String message) {
        return Mono.defer(() -> {
            RuntimeState state = state(runtimeId);
            requireText(recipientLabel, "recipientLabel");
            requireText(message, "message");
            state.cancellation().checkCancelled();
            if (recipientLabel.equals(state.context().directorLabel())) {
                return run(state, state.director().agent(), RoleType.DIRECTOR, recipientLabel,
                        state.context().directorSessionId(), ReviewStage.PLANNING, message,
                        state.director().toolTraceCollector());
            }
            RoleSubagentFactory.RoleRuntime role = state.roles().get(recipientLabel);
            if (role == null) {
                return Mono.error(new IllegalArgumentException("unknown role label: " + recipientLabel));
            }
            return run(state, role.agent(), role.roleType(), role.label(), role.sessionId(),
                    ReviewStage.INITIAL_REVIEW, message, role.toolTraceCollector());
        }).then();
    }

    @Override
    public Mono<Void> stopRoleRuns(String runtimeId) {
        return Mono.fromRunnable(() -> {
            RuntimeState state = runtimes.get(runtimeId);
            if (state == null) {
                return;
            }
            state.roles().values().forEach(role -> {
                try {
                    role.agent().interrupt();
                    LOGGER.info("ROLE_RUN_STOPPED runtimeId={} role={}", runtimeId, role.label());
                } catch (RuntimeException exception) {
                    LOGGER.warn("ROLE_RUN_STOP_FAILED runtimeId={} role={} error={}",
                            runtimeId, role.label(), exception.getMessage());
                }
            });
        });
    }

    @Override
    public Mono<Void> cancel(String runtimeId) {
        return Mono.fromRunnable(() -> {
            RuntimeState state = runtimes.get(runtimeId);
            if (state == null) {
                return;
            }
            if (state.cancelled()) {
                return;
            }
            markCancellation(state);
            try {
                state.director().agent().interrupt();
                state.roles().values().forEach(role -> role.agent().interrupt());
            } finally {
                publishLifecycle(state, RoleType.DIRECTOR, state.context().directorLabel(), "CANCELLED");
                state.emit(AgentRuntimeEventType.CANCELLED, state.context().directorLabel(), "cancelled");
            }
        }).then(close(runtimeId));
    }

    @Override
    public Mono<Void> close(String runtimeId) {
        return Mono.fromRunnable(() -> {
            RuntimeState state = runtimes.remove(runtimeId);
            if (state == null) {
                return;
            }
            try {
                state.director().agent().close();
                state.roles().values().forEach(role -> role.agent().close());
            } finally {
                if (state.cancelled()) {
                    cancelledRuntimeIds.add(runtimeId);
                }
                roleSubagentFactory.release(state.context());
                activeRuntimeByReview.remove(state.context().reviewId().value().toString(), runtimeId);
                publishLifecycle(state, RoleType.DIRECTOR, state.context().directorLabel(), "CLOSED");
                state.events().tryEmitComplete();
            }
        });
    }

    @Override
    public Mono<AgentRuntimeSession> resume(String runtimeId) {
        return Mono.fromSupplier(() -> {
            if (cancelledRuntimeIds.contains(runtimeId)) {
                throw new IllegalStateException("cancelled runtime cannot be resumed: " + runtimeId);
            }
            RuntimeState state = state(runtimeId);
            if (state.cancelled()) {
                throw new IllegalStateException("cancelled runtime cannot be resumed: " + runtimeId);
            }
            state.cancellation().resume();
            state.emit(AgentRuntimeEventType.RESUMED, state.context().directorLabel(), "resumed");
            return new AgentRuntimeSession(
                    runtimeId, state.context().userId(), state.context().directorSessionId());
        });
    }

    private Mono<Void> run(
            RuntimeState state,
            HarnessAgent agent,
            RoleType roleType,
            String agentId,
            String sessionId,
            ReviewStage stage,
            String message,
            ScoutToolTraceCollector toolTraceCollector) {
        return agent.streamEvents(message, agentContext(state.context(), sessionId))
                .doOnNext(event -> {
                    emitRawObservation(state, event, roleType, agentId, sessionId, stage, toolTraceCollector);
                })
                .doOnError(exception -> {
                    // A cooperative interrupt (review left the debate stages, or the runtime was
                    // cancelled) is a controlled stop, not a role failure.
                    if (isControlledStop(exception)) {
                        return;
                    }
                    publishLifecycle(state, roleType, agentId, "FAILED");
                    state.emit(AgentRuntimeEventType.FAILED, agentId, "agent-run-failed");
                })
                .then()
                .then(Mono.defer(() -> runInitialReviewFinalizerIfNeeded(
                        state, roleType, agentId, sessionId, stage)))
                .then(Mono.defer(() -> runDirectorConflictFinalizerIfNeeded(
                        state, roleType, agentId, sessionId)))
                .then(Mono.<Void>fromRunnable(() -> verifyInitialReviewCompletion(state, roleType)))
                .then(Mono.<Void>fromRunnable(() -> draftJudgeGateFallbackIfNeeded(state, roleType)))
                .doOnSuccess(ignored -> state.emit(AgentRuntimeEventType.MESSAGE_SENT, agentId, "completed"));
    }

    /**
     * Guarantees liveness after a judge turn: when the review is already JUDGING but the judge ended
     * without drafting a Gate (for example because no debate topic exists), the deterministic
     * GatePolicy drafts one from persisted Claims so the flow can reach WAITING_HUMAN instead of
     * stalling forever in JUDGING.
     */
    private void draftJudgeGateFallbackIfNeeded(RuntimeState state, RoleType roleType) {
        if (roleType != RoleType.JUDGE || judgeService == null || reviewDebateStore == null || reviewRegistry == null) {
            return;
        }
        if (state.cancelled()) {
            return;
        }
        Review review = reviewRegistry.find(state.context().reviewId()).orElse(null);
        if (review == null || review.stage() != ReviewStage.JUDGING) {
            return;
        }
        if (reviewDebateStore.findGateDraft(review.id()).isPresent()) {
            return;
        }
        try {
            judgeService.draftGate(review);
            LOGGER.info("JUDGE_GATE_FALLBACK_DRAFTED reviewId={} attemptNo={}",
                    state.context().reviewId().value(), state.context().attemptNo());
        } catch (RuntimeException exception) {
            LOGGER.warn("JUDGE_GATE_FALLBACK_FAILED reviewId={} attemptNo={}",
                    state.context().reviewId().value(), state.context().attemptNo(), exception);
        }
    }

    private Mono<Void> runInitialReviewFinalizerIfNeeded(
            RuntimeState state,
            RoleType roleType,
            String agentId,
            String sessionId,
            ReviewStage stage) {
        if (stage != ReviewStage.INITIAL_REVIEW
                || roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE
                || !requiresInitialReviewCompletion(state, roleType)) {
            return Mono.empty();
        }
        Review review = reviewRegistry == null ? null : reviewRegistry.find(state.context().reviewId()).orElse(null);
        if (review == null || review.stage() != ReviewStage.INITIAL_REVIEW
                || review.roleActivations().stream().anyMatch(activation -> activation.roleType() == roleType
                        && activation.initialReviewCompleted())
                || !state.initialReviewFinalizingRoles().add(roleType)) {
            return Mono.empty();
        }
        RoleSubagentFactory.RoleRuntime roleRuntime = state.roles().get(agentId);
        if (roleRuntime == null) {
            return Mono.empty();
        }
        RoleSubagentFactory.RoleFinalizerRuntime finalizer =
                roleSubagentFactory.createInitialReviewFinalizer(state.context(), roleRuntime);
        return finalizer.agent().streamEvents(
                        "调查轮次已结束。现在仅执行协议收尾：提交已有发现并调用 complete_initial_review。",
                        agentContext(state.context(), sessionId))
                .doOnNext(event -> emitRawObservation(
                        state, event, roleType, roleRuntime.label() + "-finalizer", sessionId, stage,
                        finalizer.toolTraceCollector()))
                .then()
                .timeout(Duration.ofMinutes(15))
                .doFinally(signal -> finalizer.agent().close());
    }

    private void verifyInitialReviewCompletion(RuntimeState state, RoleType roleType) {
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE
                || state.cancelled() || reviewRegistry == null || initialReviewProgressService == null) {
            return;
        }
        boolean completionRequired = requiresInitialReviewCompletion(state, roleType);
        if (!completionRequired) {
            return;
        }
        reviewRegistry.find(state.context().reviewId())
                .ifPresent(review -> {
                    if (state.cancelled()) {
                        return;
                    }
                    if (initialReviewProgressService.failIncompleteRole(
                            review, state.context().attemptNo(), roleType,
                            "The role ended without calling complete_initial_review.", state::cancelled)) {
                        throw new IllegalStateException("ROLE_INCOMPLETE: " + roleType.name()
                                + " ended without complete_initial_review");
                    }
                });
    }

    private Mono<Void> runDirectorConflictFinalizerIfNeeded(
            RuntimeState state, RoleType roleType, String agentId, String sessionId) {
        if (roleType != RoleType.DIRECTOR || state.cancelled() || reviewRegistry == null) {
            return Mono.empty();
        }
        Review review = reviewRegistry.find(state.context().reviewId()).orElse(null);
        if (review == null || review.stage() != ReviewStage.CONFLICT_DETECTION) {
            return Mono.empty();
        }
        if (!state.directorConflictFinalizing().compareAndSet(false, true)) {
            return Mono.empty();
        }
        ReviewDirectorHarnessFactory.DirectorFinalizerRuntime finalizer;
        try {
            finalizer = directorFactory.createNoConflictFinalizer(state.context());
        } catch (IllegalStateException unavailable) {
            return Mono.fromRunnable(() -> verifyDirectorConflictFinalization(state));
        }
        return finalizer.agent().streamEvents(
                        "Director conflict analysis ended without a stage tool. Complete the only authorized no-conflict transition now.",
                        agentContext(state.context(), sessionId))
                .doOnNext(event -> emitRawObservation(
                        state, event, RoleType.DIRECTOR, agentId + "-conflict-finalizer", sessionId,
                        ReviewStage.CONFLICT_DETECTION, finalizer.toolTraceCollector()))
                .then()
                .timeout(Duration.ofMinutes(3))
                .onErrorResume(failure -> Mono.<Void>fromRunnable(() -> verifyDirectorConflictFinalization(state))
                        .then(Mono.<Void>error(failure)))
                .then(Mono.<Void>fromRunnable(() -> verifyDirectorConflictFinalization(state)))
                .doFinally(signal -> finalizer.agent().close());
    }

    private void verifyDirectorConflictFinalization(RuntimeState state) {
        reviewRegistry.find(state.context().reviewId()).ifPresent(review -> {
            synchronized (review) {
                if (state.cancelled() || review.attemptNo() != state.context().attemptNo()
                        || review.stage() != ReviewStage.CONFLICT_DETECTION) {
                    return;
                }
                review.transitionTo(new ReviewStateMachine(), ReviewStage.FAILED);
                eventPublisher.publish(ReviewEventDrafts.completedCommand(
                        review,
                        ReviewEventType.REVIEW_FAILED,
                        RoleType.DIRECTOR,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("failureType", "DIRECTOR_CONFLICT_INCOMPLETE")));
                throw new IllegalStateException("DIRECTOR_INCOMPLETE: conflict stage ended without a transition");
            }
        });
    }

    private boolean requiresInitialReviewCompletion(RuntimeState state, RoleType roleType) {
        return state.roles().values().stream()
                .filter(role -> role.roleType() == roleType)
                .anyMatch(role -> role.rolePack().allowedTools().contains("complete_initial_review"));
    }

    private void markCancellation(RuntimeState state) {
        if (reviewRegistry == null) {
            state.cancellation().cancel();
            return;
        }
        reviewRegistry.find(state.context().reviewId())
                .filter(review -> review.attemptNo() == state.context().attemptNo())
                .ifPresentOrElse(review -> {
                    synchronized (review) {
                        state.cancellation().cancel();
                    }
                }, () -> state.cancellation().cancel());
    }

    private RuntimeContext agentContext(ReviewRuntimeContext context, String sessionId) {
        return RuntimeContext.builder()
                .userId(context.userId())
                .sessionId(sessionId)
                .put(ReviewRuntimeContext.class, context)
                .build();
    }

    private void emitRawObservation(
            RuntimeState state,
            AgentEvent event,
            RoleType roleType,
            String agentId,
            String sessionId,
            ReviewStage stage,
            ScoutToolTraceCollector toolTraceCollector) {
        if (runtimeTraceRegistry != null && agUiEventMapper != null) {
            agUiEventMapper.map(event, state.context(), roleType, agentId, null, toolTraceCollector)
                    .forEach(agUiEvent -> runtimeTraceRegistry.publish(state.context().runtimeId(), agUiEvent));
        }
        eventAdapter.adapt(event, state.context(), roleType, agentId, sessionId, stage)
                .ifPresent(observation -> state.emit(
                        AgentRuntimeEventType.RAW_EVENT,
                        observation.agentId(),
                        observation.rawEventType()));
    }

    private void publishLifecycle(RuntimeState state, RoleType roleType, String agentId, String lifecycle) {
        if (runtimeTraceRegistry != null && agUiEventMapper != null) {
            runtimeTraceRegistry.publish(
                    state.context().runtimeId(),
                    agUiEventMapper.lifecycle(state.context(), roleType, agentId, lifecycle));
        }
    }

    private RuntimeState state(String runtimeId) {
        RuntimeState state = runtimes.get(runtimeId);
        if (state == null) {
            throw new IllegalArgumentException("unknown runtime: " + runtimeId);
        }
        return state;
    }

    private boolean sameRuntimeIdentity(ReviewRuntimeContext active, ReviewRuntimeContext requested) {
        if (requested == null) {
            return false;
        }
        return active.reviewId().equals(requested.reviewId())
                && active.attemptNo() == requested.attemptNo()
                && active.userId().equals(requested.userId())
                && active.traceId().equals(requested.traceId());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static AgentScopeProperties defaultAgentScopeProperties() {
        return new AgentScopeProperties(false, ".agentscope/state");
    }

    private static final class ScoutLimitExceededException extends RuntimeException {

        private final String reasonCode;

        private ScoutLimitExceededException(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }

        private String reasonCode() {
            return reasonCode;
        }
    }

    /** Enforces the fixed init-style retrieval sequence independently of model prompt compliance. */
    private static final class ScoutInitToolBudget {

        private final int totalLimit;
        private final Map<String, Integer> callsByTool = new HashMap<>();
        private int totalCalls;

        private ScoutInitToolBudget(int totalLimit) {
            this.totalLimit = totalLimit;
        }

        private synchronized void consume(String toolName) {
            Integer perToolLimit = SCOUT_INIT_TOOL_LIMITS.get(toolName);
            if (perToolLimit == null) {
                throw new ScoutLimitExceededException("CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED");
            }
            int nextToolCalls = callsByTool.getOrDefault(toolName, 0) + 1;
            if (nextToolCalls > perToolLimit) {
                throw new ScoutLimitExceededException("CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED");
            }
            if (++totalCalls > totalLimit) {
                throw new ScoutLimitExceededException("CONTEXT_SCOUT_TOOL_BUDGET_EXCEEDED");
            }
            callsByTool.put(toolName, nextToolCalls);
        }
    }

    private static final class RuntimeState {

        private final ReviewRuntimeContext context;
        private final ReviewCancellationToken cancellation;
        private final ReviewDirectorHarnessFactory.DirectorRuntime director;
        private final ConcurrentMap<String, RoleSubagentFactory.RoleRuntime> roles = new ConcurrentHashMap<>();
        private final Set<RoleType> initialReviewFinalizingRoles = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean directorConflictFinalizing = new AtomicBoolean();
        private final AtomicLong sequence = new AtomicLong();
        private final Sinks.Many<AgentRuntimeEvent> events = Sinks.many().replay().all();

        private RuntimeState(
                ReviewRuntimeContext context,
                ReviewCancellationToken cancellation,
                ReviewDirectorHarnessFactory.DirectorRuntime director) {
            this.context = context;
            this.cancellation = cancellation;
            this.director = director;
        }

        private ReviewRuntimeContext context() {
            return context;
        }

        private ReviewCancellationToken cancellation() {
            return cancellation;
        }

        private boolean cancelled() {
            return cancellation.isCancelled();
        }

        private ReviewDirectorHarnessFactory.DirectorRuntime director() {
            return director;
        }

        private ConcurrentMap<String, RoleSubagentFactory.RoleRuntime> roles() {
            return roles;
        }

        private Set<RoleType> initialReviewFinalizingRoles() {
            return initialReviewFinalizingRoles;
        }

        private AtomicBoolean directorConflictFinalizing() {
            return directorConflictFinalizing;
        }

        private Sinks.Many<AgentRuntimeEvent> events() {
            return events;
        }

        /**
         * Emits one runtime event. The underlying replay sink is not internally thread-safe: with
         * parallel role rounds (PLAN-020) several role threads call this concurrently, and a bare
         * tryEmitNext would fail with FAIL_NON_SERIALIZED and abort the whole review. Serializing
         * the emit plus retrying busy-loop-style keeps the observability stream live and the
         * review alive.
         */
        /**
         * Emits one runtime event. The replay sink is not internally thread-safe: parallel role
         * rounds (PLAN-020) make several role threads call this concurrently, and unsynchronized
         * emissions fail with FAIL_NON_SERIALIZED and abort the whole review. Serializing here
         * keeps the observability stream live and the review alive.
         */
        private synchronized void emit(AgentRuntimeEventType type, String source, String payload) {
            Sinks.EmitResult result = events.tryEmitNext(new AgentRuntimeEvent(
                    context.runtimeId(), sequence.incrementAndGet(), type, source, payload));
            if (result.isFailure()) {
                throw new IllegalStateException("failed to emit runtime event: " + result);
            }
        }
    }
}
