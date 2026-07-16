package ai.cc.chongming.review.support;

import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeEvent;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeRoleRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeEventType;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeSession;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeStartRequest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Deterministic in-memory runtime adapter for application-layer tests.
 *
 * @author wangli
 */
public final class FakeAgentRuntimeAdapter implements AgentRuntimeAdapter {

    private final ConcurrentMap<String, RuntimeState> states = new ConcurrentHashMap<>();

    @Override
    public Mono<AgentRuntimeSession> start(AgentRuntimeStartRequest request) {
        return Mono.defer(() -> {
            RuntimeState state = new RuntimeState(new AgentRuntimeSession(
                    request.runtimeId(), request.userId(), request.sessionId()));
            if (states.putIfAbsent(request.runtimeId(), state) != null) {
                return Mono.error(new IllegalStateException("runtime already exists: " + request.runtimeId()));
            }
            state.emit(AgentRuntimeEventType.STARTED, "director", request.initialMessage());
            return Mono.just(state.session());
        });
    }

    @Override
    public Flux<AgentRuntimeEvent> streamEvents(String runtimeId) {
        return state(runtimeId).events().asFlux();
    }

    @Override
    public Mono<Void> registerRole(AgentRuntimeRoleRequest request) {
        return Mono.fromRunnable(() -> {
            RuntimeState state = state(request.runtimeId());
            if (state.cancelled().get()) {
                throw new IllegalStateException("runtime is cancelled: " + request.runtimeId());
            }
            state.emit(AgentRuntimeEventType.ROLE_REGISTERED, request.label(), request.roleType().name());
        });
    }
    @Override
    public Mono<Void> send(String runtimeId, String recipientLabel, String message) {
        return Mono.fromRunnable(() -> {
            RuntimeState state = state(runtimeId);
            if (state.cancelled().get()) {
                throw new IllegalStateException("runtime is cancelled: " + runtimeId);
            }
            state.emit(AgentRuntimeEventType.MESSAGE_SENT, recipientLabel, message);
        });
    }

    @Override
    public Mono<Void> cancel(String runtimeId) {
        return Mono.fromRunnable(() -> {
            RuntimeState state = state(runtimeId);
            if (state.cancelled().compareAndSet(false, true)) {
                state.emit(AgentRuntimeEventType.CANCELLED, "director", "cancelled");
            }
        });
    }

    @Override
    public Mono<AgentRuntimeSession> resume(String runtimeId) {
        return Mono.fromSupplier(() -> {
            RuntimeState state = state(runtimeId);
            if (state.cancelled().compareAndSet(true, false)) {
                state.emit(AgentRuntimeEventType.RESUMED, "director", "resumed");
            }
            return state.session();
        });
    }

    private RuntimeState state(String runtimeId) {
        RuntimeState state = states.get(runtimeId);
        if (state == null) {
            throw new IllegalArgumentException("unknown runtime: " + runtimeId);
        }
        return state;
    }

    private static final class RuntimeState {

        private final AgentRuntimeSession session;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Sinks.Many<AgentRuntimeEvent> events = Sinks.many().replay().all();

        private RuntimeState(AgentRuntimeSession session) {
            this.session = session;
        }

        private AgentRuntimeSession session() {
            return session;
        }

        private AtomicBoolean cancelled() {
            return cancelled;
        }

        private Sinks.Many<AgentRuntimeEvent> events() {
            return events;
        }

        private void emit(AgentRuntimeEventType type, String source, String payload) {
            Sinks.EmitResult result = events.tryEmitNext(
                    new AgentRuntimeEvent(session.runtimeId(), sequence.incrementAndGet(), type, source, payload));
            if (result.isFailure()) {
                throw new IllegalStateException("failed to emit runtime event: " + result);
            }
        }
    }
}
