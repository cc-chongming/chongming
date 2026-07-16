package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventDraft;

/**
 * [AIREVIEW-PLAN-010#1.2] Minimal business-event port used by commands without coupling them to SSE or storage.
 *
 * @author wangli
 */
@FunctionalInterface
public interface ReviewEventPublisher {

    void publish(ReviewEventDraft draft);

    static ReviewEventPublisher noop() {
        return ignored -> {
        };
    }
}
