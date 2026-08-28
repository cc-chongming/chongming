package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-059#2] 焦点解析器：列表序第一个非终态议题即焦点；全终态/空列表为空。
 */
class DebateFocusResolverTests {

    private static final ReviewId REVIEW = new ReviewId(UUID.randomUUID());

    private static DebateTopic topic(String subject, DebateTopicStatus status) {
        return DebateTopic.restore(new TopicId(UUID.randomUUID()), REVIEW, subject, List.of(),
                status, 1, List.of(), null, null);
    }

    @Test
    void focusIsTheFirstNonTerminalTopicInListOrder() {
        DebateTopic first = topic("a.open", DebateTopicStatus.OPEN);
        DebateTopic second = topic("b.challenged", DebateTopicStatus.CHALLENGED);
        assertThat(DebateFocusResolver.focus(List.of(
                topic("z.resolved", DebateTopicStatus.RESOLVED), first, second)))
                .contains(first);
    }

    @Test
    void emptyWhenEveryTopicIsTerminal() {
        assertThat(DebateFocusResolver.focus(List.of(
                topic("a", DebateTopicStatus.RESOLVED), topic("b", DebateTopicStatus.ESCALATED))))
                .isEmpty();
        assertThat(DebateFocusResolver.focus(List.of())).isEmpty();
        assertThat(DebateFocusResolver.focus((List<DebateTopic>) null)).isEmpty();
    }
}
