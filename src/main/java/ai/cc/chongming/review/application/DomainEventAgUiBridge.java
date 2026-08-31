package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-077#2] Bridges committed review domain events into the AG-UI runtime trace stream,
 * so the frontend reviewEventFromAgUiEvent parser can consume them without any client change.
 *
 * <p>The registry is resolved through an {@link ObjectProvider} so a configuration cycle can never
 * surface during bean wiring, and every delivery failure is logged without failing the authoritative
 * domain-event commit.
 *
 * @author wangli
 */
@Component
public class DomainEventAgUiBridge implements ReviewEventListener {

    private final ObjectProvider<ReviewRuntimeTraceRegistry> registryProvider;

    public DomainEventAgUiBridge(ObjectProvider<ReviewRuntimeTraceRegistry> registryProvider) {
        this.registryProvider = Objects.requireNonNull(registryProvider, "registryProvider must not be null");
    }

    @Override
    public void onCommitted(ReviewEvent event) {
        try {
            ReviewRuntimeTraceRegistry registry = registryProvider.getIfAvailable();
            if (registry == null) {
                return;
            }
            registry.recordDomainEvent(
                    ReviewRuntimeContext.runtimeIdFor(event.reviewId(), event.attemptNo()), event);
        } catch (RuntimeException exception) {
            LOGGER.warn("DOMAIN_EVENT_BRIDGE_FAILED reviewId={} attemptNo={} type={} error={}",
                    event.reviewId(), event.attemptNo(), event.type(), exception.getMessage());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainEventAgUiBridge.class);
}
