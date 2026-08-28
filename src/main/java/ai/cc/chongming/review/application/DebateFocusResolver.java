package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * [AIREVIEW-PLAN-059#2] 议题串行辩论的焦点解析器：store 列表序第一个非终态议题即焦点，
 * 调度信封仅可为焦点议题签发，其余议题排队；焦点终态后自动前进到下一个非终态议题。
 * 纯函数实现，便于单测；列表序在 MyBatis 为 topic_id 序、内存实现为登记序，二者皆稳定。
 */
public final class DebateFocusResolver {

    private DebateFocusResolver() {
    }

    public static Optional<DebateTopic> focus(List<DebateTopic> topics) {
        if (topics == null) {
            return Optional.empty();
        }
        return topics.stream()
                .filter(topic -> topic != null && !topic.status().isTerminal())
                .findFirst();
    }

    public static Optional<DebateTopic> focus(ReviewDebateStore store, ReviewId reviewId) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return focus(store.findTopics(reviewId));
    }
}
