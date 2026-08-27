package ai.cc.chongming.review.application;

/**
 * [AIREVIEW-PLAN-038#1] Application-layer port for broadcasting server-authored runtime notices.
 *
 * <p>Orchestration decisions (plan creation, role activation, role dispatch) are published as
 * public AG-UI notices through this port. Implementations must never block or break the
 * orchestration flow: a notice that cannot be delivered is only logged.
 *
 * @author wangli
 */
@FunctionalInterface
public interface RuntimeNoticeBroadcaster {

    /**
     * [AIREVIEW-PLAN-038#1] Publishes one server-authored runtime notice.
     *
     * @param context       the review attempt runtime context
     * @param agentId       the public run/agent label the notice is addressed to
     * @param discriminator stable id discriminator so repeated notices stay distinguishable
     * @param text          the human-readable notice text
     */
    void publish(ReviewRuntimeContext context, String agentId, String discriminator, String text);

    /**
     * [AIREVIEW-PLAN-038#1] Empty implementation used when no broadcaster is configured.
     */
    static RuntimeNoticeBroadcaster noop() {
        return (context, agentId, discriminator, text) -> {
        };
    }
}
