package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.ReviewRuntimeTraceRegistry;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import io.agentscope.core.agui.event.AguiEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-038#1] Contract coverage for AG-UI runtime notice broadcasting: a published
 * notice becomes exactly the three public text events on the runtime trace, missing collaborators
 * are skipped silently and registry failures never propagate.
 *
 * @author wangli
 */
class AgUiRuntimeNoticeBroadcasterTests {

    @Test
    void publishesNoticeAsThreeTextEventsOnTheRuntimeRegistry() {
        RecordingRegistry registry = new RecordingRegistry();
        AgUiRuntimeNoticeBroadcaster broadcaster = new AgUiRuntimeNoticeBroadcaster(
                registry, new ReviewAgUiEventMapper(new RuntimeTraceRedactor()));
        ReviewRuntimeContext context = context();
        String runtimeId = context.runtimeId();

        broadcaster.publish(context, "review-1-director", "review-created-v1", "评审已创建");

        assertThat(registry.publishedRuntimeIds).containsExactly(runtimeId, runtimeId, runtimeId);
        assertThat(registry.events).hasSize(3);
        assertThat(registry.events.get(0)).isInstanceOf(AguiEvent.TextMessageStart.class);
        assertThat(registry.events.get(1)).isInstanceOf(AguiEvent.TextMessageContent.class);
        AguiEvent.TextMessageContent content = (AguiEvent.TextMessageContent) registry.events.get(1);
        assertThat(content.delta()).isEqualTo("评审已创建");
        assertThat(registry.events.get(2)).isInstanceOf(AguiEvent.TextMessageEnd.class);
    }

    @Test
    void skipsSilentlyWhenRegistryOrMapperIsMissing() {
        ReviewRuntimeContext context = context();
        AgUiRuntimeNoticeBroadcaster withoutRegistry =
                new AgUiRuntimeNoticeBroadcaster(null, new ReviewAgUiEventMapper(new RuntimeTraceRedactor()));
        AgUiRuntimeNoticeBroadcaster withoutMapper =
                new AgUiRuntimeNoticeBroadcaster(new RecordingRegistry(), null);

        assertThatCode(() -> withoutRegistry.publish(context, "review-1-director", "d", "text"))
                .doesNotThrowAnyException();
        assertThatCode(() -> withoutMapper.publish(context, "review-1-director", "d", "text"))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotPropagateRegistryFailures() {
        ReviewRuntimeContext context = context();
        RecordingRegistry throwingRegistry = new RecordingRegistry() {
            @Override
            public void publish(String runtimeId, AguiEvent event) {
                throw new IllegalStateException("trace full");
            }
        };
        AgUiRuntimeNoticeBroadcaster broadcaster = new AgUiRuntimeNoticeBroadcaster(
                throwingRegistry, new ReviewAgUiEventMapper(new RuntimeTraceRedactor()));

        assertThatCode(() -> broadcaster.publish(context, "review-1-director", "d", "text"))
                .doesNotThrowAnyException();
    }

    private static ReviewRuntimeContext context() {
        return new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "user-001", "trace-001", IntakeCancellation.neverCancelled());
    }

    /**
     * Collects published events instead of wiring the real trace machinery.
     */
    private static class RecordingRegistry extends ReviewRuntimeTraceRegistry {

        private final List<AguiEvent> events = new ArrayList<>();
        private final List<String> publishedRuntimeIds = new ArrayList<>();

        @Override
        public void publish(String runtimeId, AguiEvent event) {
            publishedRuntimeIds.add(runtimeId);
            events.add(event);
        }
    }
}
