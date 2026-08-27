package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.ReviewRuntimeTraceRegistry;
import ai.cc.chongming.review.application.RuntimeNoticeBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-038#1] Routes server-authored orchestration notices into the public AG-UI
 * runtime stream by mapping them through {@link ReviewAgUiEventMapper#publicNotice} and publishing
 * each produced event with {@link ReviewRuntimeTraceRegistry}.
 *
 * <p>Both collaborators are optional: when either is absent the notice is skipped silently, and
 * any delivery failure is logged without propagating so a notification problem never blocks
 * orchestration. The broadcaster only depends on the trace registry and the event mapper, never
 * on the runtime adapter or the orchestration service, so no constructor cycle is introduced.
 *
 * @author wangli
 */
@Component
public class AgUiRuntimeNoticeBroadcaster implements RuntimeNoticeBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgUiRuntimeNoticeBroadcaster.class);

    private final ReviewRuntimeTraceRegistry traceRegistry;
    private final ReviewAgUiEventMapper agUiEventMapper;

    /**
     * [AIREVIEW-PLAN-038#1] Direct constructor kept for tests and explicit wiring; null parameters
     * are tolerated and make publishing a silent no-op.
     */
    public AgUiRuntimeNoticeBroadcaster(
            ReviewRuntimeTraceRegistry traceRegistry, ReviewAgUiEventMapper agUiEventMapper) {
        this.traceRegistry = traceRegistry;
        this.agUiEventMapper = agUiEventMapper;
    }

    /**
     * [AIREVIEW-PLAN-038#1] Spring constructor: resolves both collaborators lazily so the bean can
     * start even when the runtime tracing support is not fully assembled.
     */
    @Autowired
    public AgUiRuntimeNoticeBroadcaster(
            ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider,
            ObjectProvider<ReviewAgUiEventMapper> agUiEventMapperProvider) {
        this(
                traceRegistryProvider == null ? null : traceRegistryProvider.getIfAvailable(),
                agUiEventMapperProvider == null ? null : agUiEventMapperProvider.getIfAvailable());
    }

    @Override
    public void publish(ReviewRuntimeContext context, String agentId, String discriminator, String text) {
        try {
            if (context == null || agentId == null || agentId.isBlank()
                    || traceRegistry == null || agUiEventMapper == null) {
                return;
            }
            agUiEventMapper.publicNotice(context, agentId, discriminator, text)
                    .forEach(event -> traceRegistry.publish(context.runtimeId(), event));
        } catch (RuntimeException exception) {
            LOGGER.warn("runtime_notice_publish_failed runtimeId={} discriminator={} error={}",
                    context == null ? "<null>" : context.runtimeId(), discriminator, exception.getMessage());
        }
    }
}
