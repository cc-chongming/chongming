package ai.cc.chongming.review.infrastructure.agentscope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Isolates the review application from AgentScope runtime API changes.
 *
 * @author wangli
 */
public interface AgentRuntimeAdapter {

    Mono<AgentRuntimeSession> start(AgentRuntimeStartRequest request);

    Flux<AgentRuntimeEvent> streamEvents(String runtimeId);

    Mono<Void> registerRole(AgentRuntimeRoleRequest request);

    Mono<Void> send(String runtimeId, String recipientLabel, String message);

    Mono<Void> cancel(String runtimeId);

    /**
     * Interrupts every role subagent still running for a runtime without cancelling the runtime
     * itself. Used when the review leaves the debate stages (for example on JUDGING_STARTED) so a
     * slow role loop stops producing output instead of continuing to attempt rejected turns.
     * Implementations must treat an unknown runtime as a no-op.
     */
    default Mono<Void> stopRoleRuns(String runtimeId) {
        return Mono.empty();
    }

    /**
     * Releases runtime-owned resources after a terminal review transition.
     * Implementations must treat an unknown or already released runtime as a no-op.
     */
    default Mono<Void> close(String runtimeId) {
        return Mono.empty();
    }

    Mono<AgentRuntimeSession> resume(String runtimeId);
}
