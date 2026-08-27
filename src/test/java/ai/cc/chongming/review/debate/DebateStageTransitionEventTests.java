package ai.cc.chongming.review.debate;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import static ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
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
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
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
 * [AIREVIEW-PLAN-022][AIREVIEW-PLAN-047#1] Round and judging stage transitions are public facts:
 * the live page derives its debate round and phase from the domain stream, so the topic-level
 * round-two transition must emit DEBATE_ROUND_2_STARTED carrying the topicId while the review
 * stays in the single DEBATE phase.
 *
 * @author wangli
 */
class DebateStageTransitionEventTests {

    @Test
    void beginTopicSecondRoundPublishesRoundTwoStartedFactWithTopicId() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService service = new DebateService(
                store, new EvidenceLedgerService(), new DebateStateMachine(),
                new ReviewProtocolGuard(), publisher);
        Review review = debateReview();
        DebateTopic topic = challengedRoundOneTopic(review.id());
        store.saveTopic(topic);
        store.saveTurn(review.id(), topic.turns().get(0));

        DebateService.TopicRoundResult result = service.beginTopicSecondRound(
                review, metadata(review, "begin-topic-round-two"), topic.id());

        assertThat(result.replayed()).isFalse();
        assertThat(result.topic().currentRound()).isEqualTo(2);
        // [AIREVIEW-PLAN-047#1] The review stage never moves: the round lives on the topic.
        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE);
        assertThat(store.findTopic(review.id(), topic.id()).orElseThrow().currentRound()).isEqualTo(2);
        ReviewEventDraft draft = publisher.only(ReviewEventType.DEBATE_ROUND_2_STARTED);
        assertThat(draft.stage()).isEqualTo(ReviewStage.DEBATE);
        assertThat(draft.topicId()).isEqualTo(topic.id());
        assertThat(draft.round()).isEqualTo(2);
        assertThat(draft.progress()).isEqualTo(65);
    }

    /** [AIREVIEW-PLAN-024#方案4 验证矩阵][AIREVIEW-PLAN-047#1] 终态议题禁止开启议题级第二轮。 */
    @Test
    void beginTopicSecondRoundRejectedWhenTopicIsTerminal() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService service = new DebateService(
                store, new EvidenceLedgerService(), new DebateStateMachine(),
                new ReviewProtocolGuard(), publisher);
        Review review = debateReview();
        DebateTopic topic = new DebateTopic(
                new ai.cc.chongming.review.domain.model.ReviewTypes.TopicId(UUID.randomUUID()),
                review.id(), "authentication", List.of(claimId()));
        topic.close(new DebateStateMachine(), DebateTopicStatus.ESCALATED, "已在第一轮收敛", Instant.now());
        store.saveTopic(topic);

        assertThatThrownBy(() -> service.beginTopicSecondRound(
                review, metadata(review, "begin-terminal"), topic.id()))
                .isInstanceOf(ReviewDomainException.class)
                .hasMessageContaining("terminal topic");
        assertThat(review.stage()).isEqualTo(ReviewStage.DEBATE);
        assertThat(topic.currentRound()).isZero();
    }

    @Test
    void beginJudgingPublishesJudgingStartedFact() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        DebateService service = new DebateService(
                store, new EvidenceLedgerService(), new DebateStateMachine(),
                new ReviewProtocolGuard(), publisher);
        Review review = debateReview();
        DebateTopic topic = challengedRoundOneTopic(review.id());
        store.saveTopic(topic);
        store.saveTurn(review.id(), topic.turns().get(0));
        service.beginTopicSecondRound(review, metadata(review, "begin-topic-round-two"), topic.id());
        topic.close(new DebateStateMachine(), DebateTopicStatus.ESCALATED, "未收敛，交由 Judge 裁决", Instant.now());

        service.beginJudging(review);

        assertThat(review.stage()).isEqualTo(ReviewStage.JUDGING);
        ReviewEventDraft draft = publisher.only(ReviewEventType.JUDGING_STARTED);
        assertThat(draft.stage()).isEqualTo(ReviewStage.JUDGING);
    }

    /** A CHALLENGED topic with an unanswered round-one challenge keeps a valid open action. */
    private DebateTopic challengedRoundOneTopic(ReviewId reviewId) {
        DebateTopic topic = new DebateTopic(
                new ai.cc.chongming.review.domain.model.ReviewTypes.TopicId(UUID.randomUUID()),
                reviewId, "authentication", List.of(claimId()));
        DebateTurn challenge = new DebateTurn(
                new ai.cc.chongming.review.domain.model.ReviewTypes.TurnId(UUID.randomUUID()),
                topic.id(), 1, RoleType.PRODUCT, RoleType.BACKEND, DebateTurnType.CHALLENGE,
                claimId(), null, "Provide refresh-token evidence.", List.of(), null, null, Instant.now());
        topic.addChallenge(new DebateStateMachine(), challenge);
        return topic;
    }

    private Review debateReview() {
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
        review.transitionTo(stateMachine, ReviewStage.DEBATE);
        return review;
    }

    private static ClaimId claimId() {
        return new ClaimId(UUID.randomUUID());
    }

    private static ReviewCommandMetadata metadata(Review review, String key) {
        return new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key));
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
