package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.AgentEventAdapter;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that raw AgentScope events remain redacted runtime telemetry.
 *
 * @author wangli
 */
class AgentEventAdapterTests {

    @Test
    void adaptsObservableEventWithOnlySafeMetadata() {
        AgentEvent event = Mockito.mock(AgentEvent.class);
        Mockito.when(event.getType()).thenReturn(AgentEventType.TOOL_CALL_START);
        Mockito.when(event.getSource()).thenReturn("role-agent");
        Mockito.when(event.getMetadata()).thenReturn(Map.of("toolName", "searchText", "parentId", "parent-001"));

        AgentEventAdapter.RuntimeObservation observation = new AgentEventAdapter().adapt(
                event, context(), RoleType.PRODUCT, "role-agent", "role-session", ReviewStage.INITIAL_REVIEW).orElseThrow();

        assertThat(observation.rawEventType()).isEqualTo("TOOL_CALL_START");
        assertThat(observation.toolName()).isEqualTo("searchText");
        assertThat(observation.parentId()).isEqualTo("parent-001");
        assertThat(observation.reviewId()).isNotBlank();
    }

    @Test
    void ignoresUnknownEventTypesForForwardCompatibility() {
        AgentEvent event = Mockito.mock(AgentEvent.class);
        Mockito.when(event.getType()).thenReturn(null);

        AgentEventAdapter adapter = new AgentEventAdapter();
        assertThat(adapter.adapt(event, context(), RoleType.PRODUCT, "role-agent", "role-session", ReviewStage.INITIAL_REVIEW))
                .isEmpty();
        assertThat(adapter.ignoredEventCount()).isEqualTo(1);
    }

    private ReviewRuntimeContext context() {
        return new ReviewRuntimeContext(new ReviewId(UUID.randomUUID()), 1, "user-001", "trace-001", IntakeCancellation.neverCancelled());
    }
}