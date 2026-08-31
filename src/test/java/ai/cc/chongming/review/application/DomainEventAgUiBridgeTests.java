package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * [AIREVIEW-PLAN-077#2] Verifies the domain-event to AG-UI bridge forwards committed events to the
 * runtime trace registry and never lets a delivery failure break the authoritative domain commit.
 *
 * @author wangli
 */
class DomainEventAgUiBridgeTests {

    @Test
    void onCommittedRecordsDomainEventForRuntime() {
        ReviewRuntimeTraceRegistry registry = mock(ReviewRuntimeTraceRegistry.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewRuntimeTraceRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        DomainEventAgUiBridge bridge = new DomainEventAgUiBridge(provider);
        ReviewEvent event = event();

        bridge.onCommitted(event);

        verify(registry).recordDomainEvent(
                ReviewRuntimeContext.runtimeIdFor(event.reviewId(), event.attemptNo()), event);
    }

    @Test
    void onCommittedSwallowsRegistryFailure() {
        ReviewRuntimeTraceRegistry registry = mock(ReviewRuntimeTraceRegistry.class);
        doThrow(new RuntimeException("simulated bridge failure"))
                .when(registry).recordDomainEvent(any(String.class), any(ReviewEvent.class));
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewRuntimeTraceRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        DomainEventAgUiBridge bridge = new DomainEventAgUiBridge(provider);

        assertThatCode(() -> bridge.onCommitted(event())).doesNotThrowAnyException();
    }

    @Test
    void onCommittedNoOpWithoutRegistry() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewRuntimeTraceRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        DomainEventAgUiBridge bridge = new DomainEventAgUiBridge(provider);

        bridge.onCommitted(event());

        verify(provider).getIfAvailable();
    }

    private static ReviewEvent event() {
        return new ReviewEvent(
                UUID.randomUUID(),
                1L,
                new ReviewId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                1,
                ReviewEventType.INITIAL_REVIEW_COMPLETED,
                ReviewEventType.INITIAL_REVIEW_COMPLETED.category(),
                ReviewStage.INITIAL_REVIEW,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                50,
                Instant.now(),
                1,
                Map.of("publicSummary", "初评完成"));
    }
}
