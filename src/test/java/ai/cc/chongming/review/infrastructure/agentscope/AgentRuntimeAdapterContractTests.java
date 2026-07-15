package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.support.FakeAgentRuntimeAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the application-facing lifecycle contract independently of AgentScope classes.
 *
 * @author wangli
 */
class AgentRuntimeAdapterContractTests {

    @Test
    void fakeAdapterEmitsOrderedLifecycleEvents() {
        AgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter();

        AgentRuntimeSession session = adapter.start(new AgentRuntimeStartRequest(
                "runtime-001", "user-001", "review-001", "start requirements review")).block();
        adapter.send("runtime-001", "requirements-reviewer", "check the acceptance criteria").block();
        adapter.cancel("runtime-001").block();
        AgentRuntimeSession resumed = adapter.resume("runtime-001").block();

        List<AgentRuntimeEvent> events = adapter.streamEvents("runtime-001").take(4).collectList().block();

        assertThat(session).isEqualTo(resumed);
        assertThat(events).extracting(AgentRuntimeEvent::sequence).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events).extracting(AgentRuntimeEvent::type).containsExactly(
                AgentRuntimeEventType.STARTED,
                AgentRuntimeEventType.MESSAGE_SENT,
                AgentRuntimeEventType.CANCELLED,
                AgentRuntimeEventType.RESUMED);
        assertThat(events.get(1).source()).isEqualTo("requirements-reviewer");
    }
}
