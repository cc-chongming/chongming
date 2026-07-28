package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewCancellationToken;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.ReviewRuntimeTraceRegistry;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
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

    private final ReviewDirectorHarnessFactory directorFactory;
    private final RoleSubagentFactory roleSubagentFactory;
    private final AgentEventAdapter eventAdapter;
    private final ReviewRegistry reviewRegistry;
    private final InitialReviewProgressService initialReviewProgressService;
    private final ReviewRuntimeTraceRegistry runtimeTraceRegistry;
    private final ReviewAgUiEventMapper agUiEventMapper;
    private final ContextScoutHarnessFactory contextScoutHarnessFactory;
    private final ConcurrentMap<String, RuntimeState> runtimes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeRuntimeByReview = new ConcurrentHashMap<>();
    private final Set<String> cancelledRuntimeIds = ConcurrentHashMap.newKeySet();

    public AgentScopeReviewRuntimeAdapter(
            ReviewDirectorHarnessFactory directorFactory,
            RoleSubagentFactory roleSubagentFactory,
            AgentEventAdapter eventAdapter) {
        this(directorFactory, roleSubagentFactory, eventAdapter, null, null, null, null, null);
    }

    public AgentScopeReviewRuntimeAdapter(
            ReviewDirectorHarnessFactory directorFactory,
            RoleSubagentFactory roleSubagentFactory,
            AgentEventAdapter eventAdapter,
            ReviewRegistry reviewRegistry,
            InitialReviewProgressService initialReviewProgressService) {
        this(directorFactory, roleSubagentFactory, eventAdapter, reviewRegistry, initialReviewProgressService, null, null, null);
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
            ContextScoutHarnessFactory contextScoutHarnessFactory) {
        this.directorFactory = Objects.requireNonNull(directorFactory, "directorFactory must not be null");
        this.roleSubagentFactory = Objects.requireNonNull(roleSubagentFactory, "roleSubagentFactory must not be null");
        this.eventAdapter = Objects.requireNonNull(eventAdapter, "eventAdapter must not be null");
        this.reviewRegistry = reviewRegistry;
        this.initialReviewProgressService = initialReviewProgressService;
        this.runtimeTraceRegistry = runtimeTraceRegistry;
        this.agUiEventMapper = agUiEventMapper;
        this.contextScoutHarnessFactory = contextScoutHarnessFactory;
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
            return scout.then(run(state, director.agent(), RoleType.DIRECTOR, context.directorLabel(),
                    context.directorSessionId(), ReviewStage.PLANNING, request.initialMessage()))
                    .thenReturn(new AgentRuntimeSession(request.runtimeId(), request.userId(), request.sessionId()));
        });
    }

    private Mono<Void> runScout(
            RuntimeState state, ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace) {
        if (contextScoutHarnessFactory == null) {
            return Mono.empty();
        }
        HarnessAgent scout = contextScoutHarnessFactory.create(context, workspace);
        state.emit(AgentRuntimeEventType.ROLE_REGISTERED, "CONTEXT_SCOUT", "CONTEXT_SCOUT");
        return scout.streamEvents("请准备本次评审的公开项目上下文。", agentContext(context, context.runtimeId() + ":context-scout"))
                .doOnNext(event -> {
                    if (event instanceof AgentResultEvent result && result.getResult() != null) {
                        String visibleResult = result.getResult().getTextContent();
                        if (visibleResult != null && !visibleResult.isBlank()) {
                            contextScoutHarnessFactory.recordResult(context, workspace, visibleResult);
                        }
                    }
                    emitRawObservation(
                            state, event, RoleType.DIRECTOR, "CONTEXT_SCOUT", context.runtimeId() + ":context-scout",
                            ReviewStage.PLANNING);
                })
                .doOnError(exception -> {
                    publishLifecycle(state, RoleType.DIRECTOR, "CONTEXT_SCOUT", "FAILED");
                    state.emit(AgentRuntimeEventType.FAILED, "CONTEXT_SCOUT", "context-scout-failed");
                })
                .then()
                .doOnSuccess(ignored -> state.emit(AgentRuntimeEventType.MESSAGE_SENT, "CONTEXT_SCOUT", "completed"))
                .doFinally(signal -> scout.close());
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
                        state.context().directorSessionId(), ReviewStage.PLANNING, message);
            }
            RoleSubagentFactory.RoleRuntime role = state.roles().get(recipientLabel);
            if (role == null) {
                return Mono.error(new IllegalArgumentException("unknown role label: " + recipientLabel));
            }
            return run(state, role.agent(), role.roleType(), role.label(), role.sessionId(),
                    ReviewStage.INITIAL_REVIEW, message);
        }).then();
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
            String message) {
        return agent.streamEvents(message, agentContext(state.context(), sessionId))
                .doOnNext(event -> emitRawObservation(state, event, roleType, agentId, sessionId, stage))
                .doOnError(exception -> {
                    publishLifecycle(state, roleType, agentId, "FAILED");
                    state.emit(AgentRuntimeEventType.FAILED, agentId, "agent-run-failed");
                })
                .then()
                .then(Mono.<Void>fromRunnable(() -> verifyInitialReviewCompletion(state, roleType)))
                .doOnSuccess(ignored -> state.emit(AgentRuntimeEventType.MESSAGE_SENT, agentId, "completed"));
    }

    private void verifyInitialReviewCompletion(RuntimeState state, RoleType roleType) {
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE
                || state.cancelled() || reviewRegistry == null || initialReviewProgressService == null) {
            return;
        }
        boolean completionRequired = state.roles().values().stream()
                .filter(role -> role.roleType() == roleType)
                .anyMatch(role -> role.rolePack().allowedTools().contains("complete_initial_review"));
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
            ReviewStage stage) {
        if (runtimeTraceRegistry != null && agUiEventMapper != null) {
            agUiEventMapper.map(event, state.context(), roleType, agentId)
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

    private static final class RuntimeState {

        private final ReviewRuntimeContext context;
        private final ReviewCancellationToken cancellation;
        private final ReviewDirectorHarnessFactory.DirectorRuntime director;
        private final ConcurrentMap<String, RoleSubagentFactory.RoleRuntime> roles = new ConcurrentHashMap<>();
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

        private Sinks.Many<AgentRuntimeEvent> events() {
            return events;
        }

        private void emit(AgentRuntimeEventType type, String source, String payload) {
            Sinks.EmitResult result = events.tryEmitNext(new AgentRuntimeEvent(
                    context.runtimeId(), sequence.incrementAndGet(), type, source, payload));
            if (result.isFailure()) {
                throw new IllegalStateException("failed to emit runtime event: " + result);
            }
        }
    }
}
