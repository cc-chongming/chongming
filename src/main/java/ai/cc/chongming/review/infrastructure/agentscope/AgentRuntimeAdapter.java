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

    Mono<Void> send(String runtimeId, String recipientLabel, String message);

    Mono<Void> cancel(String runtimeId);

    Mono<AgentRuntimeSession> resume(String runtimeId);
}
