package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.DirectorPlanRevisionPromoter;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.ReviewCancellationToken;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.application.ReviewEventDrafts;
import ai.cc.chongming.review.application.ReviewEventPublisher;
import ai.cc.chongming.review.application.ReviewOrchestrationService;
import ai.cc.chongming.review.application.ReviewRuntimeTraceRegistry;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
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
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * [AIREVIEW-PLAN-010#1.6][AIREVIEW-PLAN-023#5] AgentScope 2.0.0 adapter that owns Harness lifecycle while keeping project governance outside AgentScope.
 *
 * @author zyj
 */
@Component
public class AgentScopeReviewRuntimeAdapter implements AgentRuntimeAdapter {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeReviewRuntimeAdapter.class);

    // [AIREVIEW-PLAN-035#3.1] Per-tool limits are ADVISORY: exceeding one only logs, never aborts,
    // so the Scout can pivot to tools with remaining quota (a grep-heavy requirement must not be
    // killed while read_file budget sits unused). The hard wall is the total budget
    // (scoutMaxToolCalls) plus the ban on tools outside this contract.
    private static final Map<String, Integer> SCOUT_INIT_TOOL_LIMITS = Map.of(
            "glob_files", 3,
            "grep_files", 6,
            "read_file", 6);

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
    private final ObjectProvider<ReviewOrchestrationService> orchestrationServiceProvider;
    private volatile DirectorPlanRevisionPromoter planRevisionPromoter;
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
                ReviewEventPublisher.noop(), defaultAgentScopeProperties(), null);
    }

    public AgentScopeReviewRuntimeAdapter(
            ReviewDirectorHarnessFactory directorFactory,
            RoleSubagentFactory roleSubagentFactory,
            AgentEventAdapter eventAdapter,
            ReviewRegistry reviewRegistry,
            InitialReviewProgressService initialReviewProgressService) {
        this(directorFactory, roleSubagentFactory, eventAdapter, reviewRegistry, initialReviewProgressService,
                null, null, null, ReviewEventPublisher.noop(), defaultAgentScopeProperties(), null);
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
                defaultAgentScopeProperties(), null);
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
            AgentScopeProperties agentScopeProperties,
            ObjectProvider<ReviewOrchestrationService> orchestrationServiceProvider) {
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
        this.orchestrationServiceProvider = orchestrationServiceProvider;
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

    /**
     * [AIREVIEW-PLAN-069#4] Shares the Spring-managed plan revision promoter so terminal cleanup
     * (dispatcher COMPLETED observation) can clear its per-runtime promotion watermarks. Optional:
     * manual/test wiring keeps using the lazy {@link #planRevisionPromoter()} fallback.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configurePlanRevisionPromoter(DirectorPlanRevisionPromoter promoter) {
        this.planRevisionPromoter = promoter;
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
            AtomicReference<ContextScoutConclusion> conclusion = new AtomicReference<>();
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
                                String overQuota = nativeToolBudget.consume(toolCallStart.getToolCallName());
                                if (overQuota != null) {
                                    log.warn("context_scout_tool_over_quota reviewId={} attemptNo={} {}",
                                            state.context().reviewId(), state.context().attemptNo(), overQuota);
                                }
                            }
                            if (event instanceof AgentResultEvent result && result.getResult() != null) {
                                String visibleResult = result.getResult().getTextContent();
                                if (visibleResult != null && !visibleResult.isBlank()) {
                                    conclusion.set(contextScoutHarnessFactory.recordResult(context, workspace, visibleResult));
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
                if (!degraded.get() && conclusion.get() != null) {
                    recordScoutCompletion(state, conclusion.get());
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
        // [AIREVIEW-PLAN-025] Scout degradation used to be silent; log the root cause so the
        // advisory fallback stays diagnosable in server logs.
        // [AIREVIEW-PLAN-035#3.3] Surface the violating tool in the degradation WARN.
        String detail = failure instanceof ScoutLimitExceededException limitExceeded ? limitExceeded.detail() : null;
        log.warn(
                "context_scout_degraded reviewId={} attemptNo={} failureType={} message={} detail={}",
                state.context().reviewId(),
                state.context().attemptNo(),
                failure.getClass().getName(),
                failure.getMessage(),
                detail);
        degraded.set(true);
        recordScoutDegradation(state, failure);
        return Mono.empty();
    }

    /** Publishes the lightweight success fact only after the durable conclusion store has accepted it. */
    private void recordScoutCompletion(RuntimeState state, ContextScoutConclusion conclusion) {
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
                        ReviewEventType.CONTEXT_SCOUT_COMPLETED,
                        RoleType.DIRECTOR,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of(
                                "status", "COMPLETED",
                                "schemaVersion", Integer.toString(conclusion.schemaVersion()),
                                "publicSummary", conclusion.summary(),
                                "conclusionRef", conclusion.reference())));
            }
        });
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

    /**
     * [AIREVIEW-PLAN-070#1] Sends a plain wake/instruction message. A review role whose initial
     * review is already completed must stay silent during INITIAL_REVIEW: re-waking it only makes
     * it redo finished work and re-paste long conclusions, so the wake is skipped and logged.
     * Dispatch envelopes (see {@link #deliverDispatchCommand}) deliberately bypass this gate.
     */
    @Override
    public Mono<Void> send(String runtimeId, String recipientLabel, String message) {
        return sendInternal(runtimeId, recipientLabel, message, true);
    }

    /**
     * [AIREVIEW-PLAN-024#方案3] Injects the validated dispatch envelope into exactly the recipient
     * role's context. The envelope message already carries the commandId and the single authorized
     * write action; the role's write tools resolve the commandId server-side, so minimal delivery
     * through {@link #send} preserves the orchestration flow without restructuring this hub.
     *
     * <p>[AIREVIEW-PLAN-070#1] The completed-initial-review skip gate applies to plain wakes only:
     * debate-stage envelopes targeting a role are legal even when that role has long finished its
     * initial review, so this path never applies the {@code ROLE_WAKE_SKIPPED_COMPLETED} cut.
     */
    @Override
    public Mono<Void> deliverDispatchCommand(
            String runtimeId, String recipientLabel, String message,
            ai.cc.chongming.review.domain.model.ReviewDispatchCommand command) {
        LOGGER.info("DISPATCH_ENVELOPE_INJECTED runtimeId={} recipient={} commandId={} action={}",
                runtimeId, recipientLabel, command.commandId().value(), command.allowedAction());
        return sendInternal(runtimeId, recipientLabel, message, false);
    }

    private Mono<Void> sendInternal(
            String runtimeId, String recipientLabel, String message, boolean enforceCompletedRoleGate) {
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
            if (enforceCompletedRoleGate && shouldSkipCompletedInitialReviewWake(state, recipientLabel)) {
                LOGGER.info("ROLE_WAKE_SKIPPED_COMPLETED runtimeId={} role={} reason=initial_review_completed",
                        runtimeId, recipientLabel);
                return Mono.empty();
            }
            return run(state, role.agent(), role.roleType(), role.label(), role.sessionId(),
                    ReviewStage.INITIAL_REVIEW, message, role.toolTraceCollector());
        }).then();
    }

    /**
     * [AIREVIEW-PLAN-070#1] Plain wakes to a review role are silenced while the review is still in
     * INITIAL_REVIEW and that role's activation is already marked initialReviewCompleted, so
     * post-finalizer liveness re-wakes cannot ask it to redo finished work or re-paste conclusions.
     */
    private boolean shouldSkipCompletedInitialReviewWake(RuntimeState state, String recipientLabel) {
        if (reviewRegistry == null) {
            return false;
        }
        Review review = reviewRegistry.find(state.context().reviewId()).orElse(null);
        if (review == null || review.stage() != ReviewStage.INITIAL_REVIEW) {
            return false;
        }
        RoleType roleType = resolveRoleTypeFromLabel(state, recipientLabel);
        return roleType != null && review.roleActivations().stream()
                .anyMatch(activation -> activation.roleType() == roleType
                        && activation.initialReviewCompleted());
    }

    /**
     * [AIREVIEW-PLAN-070#1] Reverse-resolves the target role type from the recipient label with
     * the exact {@link ReviewRuntimeContext#roleLabel(RoleType)} convention used by role spawns.
     */
    private static RoleType resolveRoleTypeFromLabel(RuntimeState state, String recipientLabel) {
        for (RoleType roleType : RoleType.values()) {
            if (roleType != RoleType.DIRECTOR
                    && recipientLabel.equals(state.context().roleLabel(roleType))) {
                return roleType;
            }
        }
        return null;
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
                publishLifecycleForRuntimeActors(state, "CANCELLED");
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
                publishLifecycleForRuntimeActors(state, "CLOSED");
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
                    promoteDirectorPlanDocumentOnWrite(state, roleType, event);
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
                .then(Mono.<Void>fromRunnable(() -> promoteDirectorPlanRevisions(state, roleType)))
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
                        "调查轮次已结束。现在仅执行协议收尾：先用 submit_assessment 补齐尚缺的检查点结论"
                                + "（已持久化的检查点不要重复提交），再调用 complete_initial_review。",
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
            agUiEventMapper.lifecycle(state.context(), roleType, agentId, lifecycle)
                    .forEach(event -> runtimeTraceRegistry.publish(state.context().runtimeId(), event));
        }
    }

    private void publishLifecycleForRuntimeActors(RuntimeState state, String lifecycle) {
        publishLifecycle(state, RoleType.DIRECTOR, state.context().directorLabel(), lifecycle);
        state.roles().values().forEach(role ->
                publishLifecycle(state, role.roleType(), role.label(), lifecycle));
    }

    /**
     * [AIREVIEW-PLAN-036#闭环] Triggers an immediate plan-document scan when the Director's
     * plan_write tool completed successfully, so the plan card updates as soon as the model
     * actually rewrites plans/PLAN.md rather than waiting for the whole wake round to end.
     */
    private void promoteDirectorPlanDocumentOnWrite(
            RuntimeState state, RoleType roleType, AgentEvent event) {
        if (roleType != RoleType.DIRECTOR || state.cancelled()) {
            return;
        }
        if (event instanceof ToolResultEndEvent toolEnd
                && "plan_write".equals(toolEnd.getToolCallName())
                && toolEnd.getState() != ToolResultState.ERROR
                && toolEnd.getState() != ToolResultState.DENIED) {
            promoteDirectorPlanRevisions(state, roleType);
        }
    }

    /**
     * Scans the Director's plan document at a safe observation point (plan_write completion or
     * round end) and promotes a new/changed document into a public PLAN_REVISED revision. The
     * promoter is digest-idempotent, so both hooks promote a given document at most once.
     */
    private void promoteDirectorPlanRevisions(RuntimeState state, RoleType roleType) {
        if (roleType != RoleType.DIRECTOR || state.cancelled() || planRevisionPromoter() == null) {
            return;
        }
        try {
            planRevisionPromoter().promoteIfChanged(state.context(), state.director().workspace())
                    .ifPresent(revision -> publishPlanRevisionNotice(state, revision));
        } catch (RuntimeException exception) {
            LOGGER.warn("director_plan_revision_promotion_failed runtimeId={} error={}",
                    state.context().runtimeId(), exception.getMessage());
        }
    }

    /**
     * Lazily resolves the shared promoter. The ObjectProvider defers
     * {@link ReviewOrchestrationService} construction so this adapter and the orchestration
     * service never create a constructor cycle.
     */
    private DirectorPlanRevisionPromoter planRevisionPromoter() {
        DirectorPlanRevisionPromoter resolved = planRevisionPromoter;
        if (resolved != null) {
            return resolved;
        }
        if (orchestrationServiceProvider == null) {
            return null;
        }
        ReviewOrchestrationService orchestrationService = orchestrationServiceProvider.getIfAvailable();
        if (orchestrationService == null) {
            return null;
        }
        DirectorPlanRevisionPromoter created = new DirectorPlanRevisionPromoter(orchestrationService);
        planRevisionPromoter = created;
        return created;
    }

    /**
     * Publishes "协调者修订评审计划至 vN" into the public AG-UI runtime stream under the Director
     * run and records the revision on the runtime event stream. The PLAN_REVISED domain event
     * (emitted by {@link ReviewOrchestrationService#revisePlan}) refreshes the plan cards; this
     * notice makes the revision visible in the run flow while it happens.
     */
    private void publishPlanRevisionNotice(
            RuntimeState state, ReviewOrchestrationService.PlanRevision revision) {
        String label = state.context().directorLabel();
        String version = Integer.toString(revision.plan().planVersion());
        String text = "协调者修订评审计划至 v" + version;
        if (runtimeTraceRegistry != null && agUiEventMapper != null) {
            agUiEventMapper.publicNotice(state.context(), label, "plan-revised-v" + version, text)
                    .forEach(event -> runtimeTraceRegistry.publish(state.context().runtimeId(), event));
        }
        state.emit(AgentRuntimeEventType.RAW_EVENT, label, "plan-revised-v" + version);
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

    /** [AIREVIEW-PLAN-035#3.2] Carries the violating tool detail so degradations stay diagnosable. */
    private static final class ScoutLimitExceededException extends RuntimeException {

        private final String reasonCode;
        private final String detail;

        private ScoutLimitExceededException(String reasonCode) {
            this(reasonCode, null);
        }

        private ScoutLimitExceededException(String reasonCode, String detail) {
            super(reasonCode);
            this.reasonCode = reasonCode;
            this.detail = detail;
        }

        private String reasonCode() {
            return reasonCode;
        }

        private String detail() {
            return detail;
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

        /**
         * Returns advisory over-quota detail when a per-tool limit is exceeded so the caller can log
         * it; throws only for banned tools or a depleted total budget. Over-quota calls still run,
         * because the model may pivot to other tools with remaining budget.
         */
        private synchronized String consume(String toolName) {
            Integer perToolLimit = SCOUT_INIT_TOOL_LIMITS.get(toolName);
            if (perToolLimit == null) {
                throw new ScoutLimitExceededException("CONTEXT_SCOUT_INIT_CONTRACT_VIOLATED",
                        "violatingTool=" + toolName + " reason=not-in-init-retrieval-contract");
            }
            if (++totalCalls > totalLimit) {
                throw new ScoutLimitExceededException("CONTEXT_SCOUT_TOOL_BUDGET_EXCEEDED",
                        "violatingTool=" + toolName + " totalCalls=" + totalCalls + " totalLimit=" + totalLimit);
            }
            int nextToolCalls = callsByTool.getOrDefault(toolName, 0) + 1;
            callsByTool.put(toolName, nextToolCalls);
            return nextToolCalls > perToolLimit
                    ? "tool=" + toolName + " calls=" + nextToolCalls + " perToolLimit=" + perToolLimit
                    : null;
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
