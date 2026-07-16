package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;

/**
 * [AIREVIEW-PLAN-010#1.4] Receives already-committed events for non-authoritative live delivery.
 *
 * @author wangli
 */
@FunctionalInterface
public interface ReviewEventListener {

    void onCommitted(ReviewEvent event);
}
