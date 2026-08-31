package ai.cc.chongming.review.debate;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import static ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import static ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import static ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.ReviewEventPublisher;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
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
 * [AIREVIEW-PLAN-047#1] Topic-level second round: success opens round two for exactly one topic,
 * the same command replays idempotently, terminal and pre-round-one topics are rejected, and a
 * topic already on round two can never start a third (hard two-round cap).
 *
 * @author wangli
 */
class DebateServiceTopicRoundTests {

    @Test
    void beginsSecondRoundForOneTopicAndLeavesTheReviewInDebate() {
        Fixture fixture = fixture();
        DebateTopic topic = challenged(fixture.store(), fixture.review().id(), "authentication");

        DebateService.TopicRoundResult result = fixture.service().beginTopicSecondRound(
                fixture.review(), metadata(fixture.review(), "topic-round-two"), topic.id());

        assertThat(result.replayed()).isFalse();
        assertThat(result.topic().currentRound()).isEqualTo(2);
        assertThat(fixture.review().stage()).isEqualTo(ReviewStage.DEBATE);
        assertThat(fixture.store().findTopic(fixture.review().id(), topic.id()).orElseThrow().currentRound())
                .isEqualTo(2);
        ReviewEventDraft draft = fixture.publisher().only(ReviewEventType.DEBATE_ROUND_2_STARTED);
        assertThat(draft.topicId()).isEqualTo(topic.id());
        assertThat(draft.round()).isEqualTo(2);
    }

    @Test
    void replayingTheSameCommandIsIdempotent() {
        Fixture fixture = fixture();
        DebateTopic topic = challenged(fixture.store(), fixture.review().id(), "authentication");
        ReviewCommandMetadata metadata = metadata(fixture.review(), "topic-round-two");

        DebateService.TopicRoundResult first =
                fixture.service().beginTopicSecondRound(fixture.review(), metadata, topic.id());
        DebateService.TopicRoundResult replay =
                fixture.service().beginTopicSecondRound(fixture.review(), metadata, topic.id());

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.topic().currentRound()).isEqualTo(2);
        assertThat(fixture.publisher().drafts(ReviewEventType.DEBATE_ROUND_2_STARTED)).hasSize(1);
    }

    @Test
    void rejectsTerminalTopicEvenUnderAFreshCommandKey() {
        Fixture fixture = fixture();
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), fixture.review().id(),
                "authentication", List.of(claimId()));
        topic.close(new DebateStateMachine(), DebateTopicStatus.ESCALATED, "已在第一轮收敛", Instant.now());
        fixture.store().saveTopic(topic);

        assertThatThrownBy(() -> fixture.service().beginTopicSecondRound(
                fixture.review(), metadata(fixture.review(), "terminal-topic-round-two"), topic.id()))
                .isInstanceOf(ReviewDomainException.class)
                .hasMessageContaining("terminal topic");
        assertThat(fixture.review().stage()).isEqualTo(ReviewStage.DEBATE);
        assertThat(topic.currentRound()).isZero();
    }

    @Test
    void rejectsSecondRoundBeforeTheTopicCompletedRoundOne() {
        Fixture fixture = fixture();
        // A fresh OPEN topic never saw a round-one turn (currentRound 0).
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), fixture.review().id(),
                "authentication", List.of(claimId()));
        fixture.store().saveTopic(topic);

        assertThatThrownBy(() -> fixture.service().beginTopicSecondRound(
                fixture.review(), metadata(fixture.review(), "pre-round-one"), topic.id()))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.DEBATE_ROUND_EXCEEDED);
    }

    @Test
    void rejectsNoOpenActionRoundsAtTopicLevel() {
        Fixture fixture = fixture();
        DebateTopic topic = fullyAnsweredRoundOne(fixture.review().id());
        fixture.store().saveTopic(topic);
        for (DebateTurn turn : topic.turns()) {
            fixture.store().saveTurn(fixture.review().id(), turn);
        }

        assertThatThrownBy(() -> fixture.service().beginTopicSecondRound(
                fixture.review(), metadata(fixture.review(), "empty-topic-round-two"), topic.id()))
                .isInstanceOf(ReviewDomainException.class)
                .hasMessageContaining("empty second round");
        assertThat(topic.currentRound()).isEqualTo(1);
    }

    /** [AIREVIEW-PLAN-047#1] Hard two-round cap: a topic already on round two is only replayed. */
    @Test
    void thirdRoundIsNeverStartedForATopicAlreadyOnRoundTwo() {
        Fixture fixture = fixture();
        DebateTopic topic = challenged(fixture.store(), fixture.review().id(), "authentication");
        fixture.service().beginTopicSecondRound(
                fixture.review(), metadata(fixture.review(), "one"), topic.id());
        int eventsAfterFirst = fixture.publisher().drafts(ReviewEventType.DEBATE_ROUND_2_STARTED).size();

        // A fresh command key on the same round-two topic must not open a third round.
        DebateService.TopicRoundResult third = fixture.service().beginTopicSecondRound(
                fixture.review(), metadata(fixture.review(), "two"), topic.id());

        assertThat(third.replayed()).isTrue();
        assertThat(third.topic().currentRound()).isEqualTo(2);
        assertThat(fixture.publisher().drafts(ReviewEventType.DEBATE_ROUND_2_STARTED))
                .hasSize(eventsAfterFirst);
        assertThat(fixture.review().stage()).isEqualTo(ReviewStage.DEBATE);
    }

    @Test
    void rebuttedTopicWithUnwithdrawnP1OpposeClaimBeginsSecondRound() {
        Fixture fixture = fixture();
        Claim p1 = claim(claimId(), fixture.review().id(), ClaimSeverity.P1, ClaimStatus.SUBMITTED);
        DebateTopic topic = rebuttedTopicWithClaims(fixture.store(), fixture.review().id(), List.of(p1));

        DebateService.TopicRoundResult result = fixture.service().beginTopicSecondRound(
                fixture.review(), metadata(fixture.review(), "rebutted-p1-oppose-round-two"), topic.id());

        assertThat(result.replayed()).isFalse();
        assertThat(result.topic().currentRound()).isEqualTo(2);
        assertThat(fixture.store().findTopic(fixture.review().id(), topic.id()).orElseThrow().currentRound())
                .isEqualTo(2);
        ReviewEventDraft draft = fixture.publisher().only(ReviewEventType.DEBATE_ROUND_2_STARTED);
        assertThat(draft.topicId()).isEqualTo(topic.id());
    }

    @Test
    void rebuttedTopicWithOnlyP2OrP3OpposeClaimsRejectsSecondRound() {
        Fixture fixture = fixture();
        Claim p2 = claim(claimId(), fixture.review().id(), ClaimSeverity.P2, ClaimStatus.SUBMITTED);
        Claim p3 = claim(claimId(), fixture.review().id(), ClaimSeverity.P3, ClaimStatus.SUBMITTED);
        DebateTopic topic = rebuttedTopicWithClaims(fixture.store(), fixture.review().id(), List.of(p2, p3));

        assertThatThrownBy(() -> fixture.service().beginTopicSecondRound(
                fixture.review(), metadata(fixture.review(), "rebutted-p2-p3-round-two"), topic.id()))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
        assertThat(topic.currentRound()).isEqualTo(1);
    }

    private DebateTopic rebuttedTopicWithClaims(
            InMemoryReviewDebateStore store, ReviewId reviewId, List<Claim> claims) {
        List<ClaimId> claimIds = claims.stream().map(Claim::claimId).toList();
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), reviewId, "authentication", claimIds);
        ClaimId targetClaimId = claims.getFirst().claimId();
        DebateTurn challenge = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), 1,
                RoleType.PRODUCT, RoleType.BACKEND, DebateTurnType.CHALLENGE, targetClaimId, null,
                "提供刷新令牌证据。", List.of(), null, null, Instant.now());
        DebateTurn rebuttal = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), 1,
                RoleType.BACKEND, RoleType.PRODUCT, DebateTurnType.REBUTTAL, null, challenge.turnId(),
                "后端已给出策略说明。", List.of(), null, null, Instant.now());
        topic.addChallenge(new DebateStateMachine(), challenge);
        topic.addRebuttal(new DebateStateMachine(), rebuttal);
        claims.forEach(store::saveClaim);
        store.saveTopic(topic);
        store.saveTurn(reviewId, challenge);
        store.saveTurn(reviewId, rebuttal);
        return topic;
    }

    private static Claim claim(ClaimId claimId, ReviewId reviewId, ClaimSeverity severity, ClaimStatus status) {
        return new Claim(claimId, reviewId, RoleType.BACKEND, "authentication", severity,
                ClaimPosition.OPPOSE, "后端反对该方案。", "存在残余风险。", List.of(), status);
    }

    // --- fixtures -----------------------------------------------------------

    private Fixture fixture() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        RecordingPublisher publisher = new RecordingPublisher();
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
        DebateService service = new DebateService(
                store, new EvidenceLedgerService(), new DebateStateMachine(),
                new ReviewProtocolGuard(), publisher);
        return new Fixture(review, store, service, publisher);
    }

    private DebateTopic challenged(InMemoryReviewDebateStore store, ReviewId reviewId, String subjectKey) {
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), reviewId, subjectKey, List.of(claimId()));
        DebateTurn challenge = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), 1,
                RoleType.PRODUCT, RoleType.BACKEND, DebateTurnType.CHALLENGE, claimId(), null,
                "提供刷新令牌证据。", List.of(), null, null, Instant.now());
        topic.addChallenge(new DebateStateMachine(), challenge);
        store.saveTopic(topic);
        store.saveTurn(reviewId, challenge);
        return topic;
    }

    private DebateTopic fullyAnsweredRoundOne(ReviewId reviewId) {
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), reviewId, "authentication", List.of(claimId()));
        DebateTurn challenge = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), 1,
                RoleType.PRODUCT, RoleType.BACKEND, DebateTurnType.CHALLENGE, claimId(), null,
                "提供刷新令牌证据。", List.of(), null, null, Instant.now());
        DebateTurn rebuttal = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), 1,
                RoleType.BACKEND, RoleType.PRODUCT, DebateTurnType.REBUTTAL, null, challenge.turnId(),
                "后端已给出策略说明。", List.of(), null, null, Instant.now());
        topic.addChallenge(new DebateStateMachine(), challenge);
        topic.addRebuttal(new DebateStateMachine(), rebuttal);
        return topic;
    }

    private static ClaimId claimId() {
        return new ClaimId(UUID.randomUUID());
    }

    private static ReviewCommandMetadata metadata(Review review, String key) {
        return new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key));
    }

    private static final class Fixture {
        private final Review review;
        private final InMemoryReviewDebateStore store;
        private final DebateService service;
        private final RecordingPublisher publisher;

        private Fixture(Review review, InMemoryReviewDebateStore store, DebateService service,
                RecordingPublisher publisher) {
            this.review = review;
            this.store = store;
            this.service = service;
            this.publisher = publisher;
        }

        private Review review() { return review; }
        private InMemoryReviewDebateStore store() { return store; }
        private DebateService service() { return service; }
        private RecordingPublisher publisher() { return publisher; }
    }

    private static final class RecordingPublisher implements ReviewEventPublisher {
        private final List<ReviewEventDraft> drafts = new ArrayList<>();

        @Override
        public void publish(ReviewEventDraft draft) {
            drafts.add(draft);
        }

        private List<ReviewEventDraft> drafts(ReviewEventType type) {
            return drafts.stream().filter(draft -> draft.type() == type).toList();
        }

        private ReviewEventDraft only(ReviewEventType type) {
            return drafts(type).stream().findFirst().orElseThrow();
        }
    }
}
