package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewCancellationToken;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * AgentScope 2.0.0 adapter that owns Harness lifecycle while keeping project governance outside AgentScope.
 *
 * @author wangli
 */
@Component
public class AgentScopeReviewRuntimeAdapter implements AgentRuntimeAdapter {

    private final ReviewDirectorHarnessFactory directorFactory;
    private final RoleSubagentFactory roleSubagentFactory;
    private final AgentEventAdapter eventAdapter;
    private final ConcurrentMap<String, RuntimeState> runtimes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeRuntimeByReview = new ConcurrentHashMap<>();

    public AgentScopeReviewRuntimeAdapter(
            ReviewDirectorHarnessFactory directorFactory,
            RoleSubagentFactory roleSubagentFactory,
            AgentEventAdapter eventAdapter) {
        this.directorFactory = Objects.requireNonNull(directorFactory, "directorFactory must not be null");
        this.roleSubagentFactory = Objects.requireNonNull(roleSubagentFactory, "roleSubagentFactory must not be null");
        this.eventAdapter = Objects.requireNonNull(eventAdapter, "eventAdapter must not be null");
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
            return run(state, director.agent(), RoleType.DIRECTOR, context.directorLabel(),
                    context.directorSessionId(), ReviewStage.PLANNING, request.initialMessage())
                    .thenReturn(new AgentRuntimeSession(request.runtimeId(), request.userId(), request.sessionId()));
        });
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
            if (!state.context().equals(request.runtimeContext())) {
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
            RuntimeState state = state(runtimeId);
            if (state.cancelled()) {
                return;
            }
            state.cancellation().cancel();
            try {
                state.director().agent().interrupt();
                state.roles().values().forEach(role -> role.agent().interrupt());
            } finally {
                activeRuntimeByReview.remove(state.context().reviewId().value().toString(), runtimeId);
                state.emit(AgentRuntimeEventType.CANCELLED, state.context().directorLabel(), "cancelled");
            }
        });
    }

    @Override
    public Mono<AgentRuntimeSession> resume(String runtimeId) {
        return Mono.fromSupplier(() -> {
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
                .doOnError(exception -> state.emit(AgentRuntimeEventType.FAILED, agentId, "agent-run-failed"))
                .then()
                .doOnSuccess(ignored -> state.emit(AgentRuntimeEventType.MESSAGE_SENT, agentId, "completed"));
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
        eventAdapter.adapt(event, state.context(), roleType, agentId, sessionId, stage)
                .ifPresent(observation -> state.emit(
                        AgentRuntimeEventType.RAW_EVENT,
                        observation.agentId(),
                        observation.rawEventType()));
    }

    private RuntimeState state(String runtimeId) {
        RuntimeState state = runtimes.get(runtimeId);
        if (state == null) {
            throw new IllegalArgumentException("unknown runtime: " + runtimeId);
        }
        return state;
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
