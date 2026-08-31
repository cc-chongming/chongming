package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
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

    /**
     * [AIREVIEW-PLAN-074#3] 乱序 UUID 登记三个 topic，且登记序第二个已终态：内存 store 必须返回
     * 登记序，焦点必须是登记序中的第一个非终态（此处即首个 OPEN）。
     */
    @Test
    void inMemoryStoreReturnsRegistrationOrderAndFocusIsFirstOpenTopic() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        TopicId firstId = new TopicId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        TopicId secondId = new TopicId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        TopicId thirdId = new TopicId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        DebateTopic firstOpen = DebateTopic.restore(firstId, REVIEW, "first.open", List.of(),
                DebateTopicStatus.OPEN, 1, List.of(), null, null);
        DebateTopic secondResolved = DebateTopic.restore(secondId, REVIEW, "second.resolved", List.of(),
                DebateTopicStatus.RESOLVED, 1, List.of(), "已解决", null);
        DebateTopic thirdOpen = DebateTopic.restore(thirdId, REVIEW, "third.open", List.of(),
                DebateTopicStatus.OPEN, 1, List.of(), null, null);

        store.saveTopic(firstOpen);
        store.saveTopic(secondResolved);
        store.saveTopic(thirdOpen);

        assertThat(store.findTopics(REVIEW))
                .extracting(DebateTopic::id)
                .containsExactly(firstId, secondId, thirdId);
        assertThat(DebateFocusResolver.focus(store, REVIEW)).contains(firstOpen);
    }
}
