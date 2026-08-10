package ai.cc.chongming.review.debate;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import static ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.ReviewEventPublisher;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-022] Round and judging stage transitions are public facts: the live page derives
 * its debate round and phase from the domain stream, so a bare state-machine transition without an
 * event would keep the page on the previous round until the next turn commits.
 *
 * @author wangli
 */
class DebateStageTransitionEventTests {

    @Test
    void beginSecondRoundPublishesRoundTwoStartedFact() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService service = new DebateService(
                store, new EvidenceLedgerService(), new DebateStateMachine(),
                new ReviewProtocolGuard(), publisher);
        Review review = debateRoundOneReview();
        // [AIREVIEW-PLAN-024#方案4] An OPEN topic keeps a valid open action so round two may start.
        store.saveTopic(new DebateTopic(
                new ai.cc.chongming.review.domain.model.ReviewTypes.TopicId(UUID.randomUUID()),
                review.id(), "authentication", List.of(claimId())));

        service.beginSecondRound(review);

        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_2);
        ReviewEventDraft draft = publisher.only(ReviewEventType.DEBATE_ROUND_2_STARTED);
        assertThat(draft.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_2);
        assertThat(draft.round()).isEqualTo(2);
        assertThat(draft.progress()).isEqualTo(65);
    }

    /** [AIREVIEW-PLAN-024#方案4 验证矩阵] 无有效开放动作时禁止 0 动作空回合。 */
    @Test
    void beginSecondRoundRejectedWhenNoOpenActionRemains() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService service = new DebateService(
                store, new EvidenceLedgerService(), new DebateStateMachine(),
                new ReviewProtocolGuard(), publisher);
        Review review = debateRoundOneReview();
        DebateTopic terminal = new DebateTopic(
                new ai.cc.chongming.review.domain.model.ReviewTypes.TopicId(UUID.randomUUID()),
                review.id(), "authentication", List.of(claimId()));
        terminal.close(new DebateStateMachine(), DebateTopicStatus.ESCALATED, "已在第一轮收敛", Instant.now());
        store.saveTopic(terminal);

        assertThatThrownBy(() -> service.beginSecondRound(review))
                .isInstanceOf(ReviewDomainException.class)
                .hasMessageContaining("empty second round");
        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);
    }

    @Test
    void beginJudgingPublishesJudgingStartedFact() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService service = new DebateService(
                store, new EvidenceLedgerService(), new DebateStateMachine(),
                new ReviewProtocolGuard(), publisher);
        Review review = debateRoundOneReview();
        DebateTopic topic = new DebateTopic(
                new ai.cc.chongming.review.domain.model.ReviewTypes.TopicId(UUID.randomUUID()),
                review.id(), "security-baseline", List.of(claimId()));
        // The OPEN topic must exist before round two begins; closing it later makes judging legal.
        store.saveTopic(topic);
        service.beginSecondRound(review);
        topic.close(new DebateStateMachine(), DebateTopicStatus.ESCALATED, "未收敛，交由 Judge 裁决", Instant.now());

        service.beginJudging(review);

        assertThat(review.stage()).isEqualTo(ReviewStage.JUDGING);
        ReviewEventDraft draft = publisher.only(ReviewEventType.JUDGING_STARTED);
        assertThat(draft.stage()).isEqualTo(ReviewStage.JUDGING);
    }

    private Review debateRoundOneReview() {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        review.transitionTo(stateMachine, ReviewStage.INITIAL_REVIEW);
        review.activateRole(new RoleActivation(RoleType.PRODUCT, "product-agent", true));
        review.activateRole(new RoleActivation(RoleType.PROJECT, "project-agent", true));
        review.activateRole(new RoleActivation(RoleType.FRONTEND, "frontend-agent", true));
        review.activateRole(new RoleActivation(RoleType.BACKEND, "backend-agent", true));
        review.transitionTo(stateMachine, ReviewStage.CONFLICT_DETECTION);
        review.transitionTo(stateMachine, ReviewStage.DEBATE_ROUND_1);
        return review;
    }

    private static ClaimId claimId() {
        return new ClaimId(UUID.randomUUID());
    }

    /** Records published drafts so tests can assert stage transitions became public facts. */
    static final class RecordingPublisher implements ReviewEventPublisher {

        private final List<ReviewEventDraft> drafts = new ArrayList<>();

        @Override
        public void publish(ReviewEventDraft draft) {
            drafts.add(draft);
        }

        ReviewEventDraft only(ReviewEventType type) {
            return drafts.stream().filter(draft -> draft.type() == type).findFirst().orElseThrow();
        }
    }
}
