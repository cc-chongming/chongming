package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.application.ClaimService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.ReviewDispatchService;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchCommandStatus;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.dispatch.InMemoryReviewDispatchStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies evidence ownership, high-severity normalization and idempotent Claim submission, plus
 * the debate-round DEFENSE dispatch gate (command required, subjectKey matched, command consumed).
 *
 * @author wangli
 */
class ClaimServiceTests {

    @Test
    void preservesInitialReviewUntilTheRoleExplicitlyCompletesAndReplaysIdempotently() {
        ClaimService service = service();
        Review review = initialReview();
        ClaimService.ClaimSubmission submission = submission(review, new IdempotencyKey("claim-product-001"), List.of());

        ClaimService.ClaimSubmissionResult accepted = service.submit(review, submission);
        ClaimService.ClaimSubmissionResult replay = service.submit(review, submission);

        assertThat(accepted.claim().status()).isEqualTo(ClaimStatus.UNVERIFIED);
        assertThat(review.roleActivations()).singleElement()
                .extracting(RoleActivation::initialReviewCompleted).isEqualTo(false);
        assertThatThrownBy(() -> service.publishInitialClaims(review))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ai.cc.chongming.review.domain.exception.ReviewErrorCode.CORE_ROLE_INITIAL_REVIEW_REQUIRED);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.claim().claimId()).isEqualTo(accepted.claim().claimId());

        review.transitionTo(new ReviewStateMachine(), ReviewStage.CONFLICT_DETECTION);
        ClaimService.ClaimSubmissionResult replayAfterStageAdvance = service.submit(review, submission);
        assertThat(replayAfterStageAdvance.replayed()).isTrue();
        assertThat(replayAfterStageAdvance.claim().claimId()).isEqualTo(accepted.claim().claimId());
    }

    @Test
    void rejectsEvidenceIdThatDoesNotBelongToCurrentReview() {
        ClaimService service = service();
        Review review = initialReview();
        ClaimService.ClaimSubmission submission = submission(
                review, new IdempotencyKey("claim-product-002"), List.of(new EvidenceId(UUID.randomUUID())));

        assertThatThrownBy(() -> service.submit(review, submission))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ai.cc.chongming.review.domain.exception.ReviewErrorCode.INVALID_EVIDENCE);
    }

    @Test
    void initialReviewStillAcceptsClaimWithoutADispatchCommand() {
        ClaimService service = service();
        Review review = initialReview();
        ClaimService.ClaimSubmission submission = submission(
                review, new IdempotencyKey("claim-product-003"), ClaimPosition.SUPPORT, "authentication");

        ClaimService.ClaimSubmissionResult accepted = service.submit(review, submission);

        assertThat(accepted.replayed()).isFalse();
        assertThat(accepted.claim().position()).isEqualTo(ClaimPosition.SUPPORT);
        assertThat(accepted.claim().subjectKey()).isEqualTo("authentication");
    }

    // --- debate-round DEFENSE gate -------------------------------------------

    @Test
    void submitsSupportClaimInDebateRoundWithValidDefenseDispatchAndConsumesTheCommand() {
        ReviewDispatchService dispatchService =
                new ReviewDispatchService(defenseDispatchStore, defenseDebateStore, ignored -> { });
        ClaimService service = new ClaimService(
                new EvidenceLedgerService(), defenseDebateStore, new ReviewProtocolGuard(),
                ai.cc.chongming.review.application.ReviewEventPublisher.noop(), dispatchService);
        Review review = debateRoundOne();
        TopicId topicId = defenseTopic(review.id());
        ReviewDispatchCommand command = issueDefense(review, topicId, "defense-valid");

        ClaimService.ClaimSubmissionResult result = service.submit(review, defenseSubmission(
                review, new IdempotencyKey("defense-claim-001"), ClaimPosition.SUPPORT,
                "authentication", command.commandId()));

        assertThat(result.replayed()).isFalse();
        assertThat(result.claim().position()).isEqualTo(ClaimPosition.SUPPORT);
        assertThat(result.claim().subjectKey()).isEqualTo("authentication");
        assertThat(defenseDispatchStore.findById(review.id(), command.commandId()))
                .get()
                .extracting(ReviewDispatchCommand::status)
                .isEqualTo(DispatchCommandStatus.CONSUMED);
        // [AIREVIEW-PLAN-040#1] The accepted DEFENSE claim is mounted onto the dispatch topic so the
        // court's support side is no longer empty.
        assertThat(defenseDebateStore.findTopic(review.id(), topicId).orElseThrow().claimIds())
                .containsExactly(result.claim().claimId());
    }

    @Test
    void initialReviewSubmissionNeverMountsClaimsOntoAnyTopic() {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        ClaimService service = new ClaimService(new EvidenceLedgerService(), store, new ReviewProtocolGuard());
        Review review = initialReview();
        TopicId topicId = new TopicId(UUID.randomUUID());
        store.saveTopic(new DebateTopic(topicId, review.id(), "authentication", List.of()));

        service.submit(review, submission(review, new IdempotencyKey("initial-claim-no-mount"),
                ClaimPosition.SUPPORT, "authentication"));

        assertThat(store.findTopic(review.id(), topicId).orElseThrow().claimIds()).isEmpty();
    }

    @Test
    void rejectsDebateRoundClaimWithoutADispatchCommand() {
        ReviewDispatchService dispatchService =
                new ReviewDispatchService(defenseDispatchStore, defenseDebateStore, ignored -> { });
        ClaimService service = new ClaimService(
                new EvidenceLedgerService(), defenseDebateStore, new ReviewProtocolGuard(),
                ai.cc.chongming.review.application.ReviewEventPublisher.noop(), dispatchService);
        Review review = debateRoundOne();
        defenseTopic(review.id());

        assertThatThrownBy(() -> service.submit(review, defenseSubmission(
                review, new IdempotencyKey("defense-claim-missing"), ClaimPosition.SUPPORT,
                "authentication", null)))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ai.cc.chongming.review.domain.exception.ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
        assertThat(defenseDispatchStore.findPending(review.id(), review.attemptNo())).isEmpty();
    }

    @Test
    void rejectsDefenseClaimWhoseSubjectKeyDoesNotMatchTheDispatchTopic() {
        ReviewDispatchService dispatchService =
                new ReviewDispatchService(defenseDispatchStore, defenseDebateStore, ignored -> { });
        ClaimService service = new ClaimService(
                new EvidenceLedgerService(), defenseDebateStore, new ReviewProtocolGuard(),
                ai.cc.chongming.review.application.ReviewEventPublisher.noop(), dispatchService);
        Review review = debateRoundOne();
        TopicId topicId = defenseTopic(review.id());
        ReviewDispatchCommand command = issueDefense(review, topicId, "defense-mismatch");

        assertThatThrownBy(() -> service.submit(review, defenseSubmission(
                review, new IdempotencyKey("defense-claim-mismatch"), ClaimPosition.SUPPORT,
                "another.subject", command.commandId())))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ai.cc.chongming.review.domain.exception.ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
        // The rejected attempt must not consume the envelope.
        assertThat(defenseDispatchStore.findById(review.id(), command.commandId()))
                .get()
                .extracting(ReviewDispatchCommand::status)
                .isEqualTo(DispatchCommandStatus.PENDING);
    }

    // --- helpers ---------------------------------------------------------------

    private final InMemoryReviewDispatchStore defenseDispatchStore = new InMemoryReviewDispatchStore();
    private final InMemoryReviewDebateStore defenseDebateStore = new InMemoryReviewDebateStore();

    private ClaimService service() {
        return new ClaimService(new EvidenceLedgerService(), new InMemoryReviewDebateStore(), new ReviewProtocolGuard());
    }

    private Review initialReview() {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        review.transitionTo(stateMachine, ReviewStage.INITIAL_REVIEW);
        review.activateRole(new RoleActivation(RoleType.PRODUCT, "product-agent", false));
        return review;
    }

    private Review debateRoundOne() {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        Review review = initialReview();
        review.transitionTo(stateMachine, ReviewStage.CONFLICT_DETECTION);
        review.transitionTo(stateMachine, ReviewStage.DEBATE_ROUND_1);
        return review;
    }

    private TopicId defenseTopic(ReviewId reviewId) {
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), reviewId,
                "authentication", List.of());
        defenseDebateStore.saveTopic(topic);
        return topic.id();
    }

    private ReviewDispatchCommand issueDefense(Review review, TopicId topicId, String key) {
        ReviewDispatchService.DispatchIssueResult issued = new ReviewDispatchService(
                defenseDispatchStore, defenseDebateStore, ignored -> { })
                .issue(review, new ReviewDispatchService.DispatchProposal(
                        new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key)),
                        RoleType.PRODUCT, DispatchedAction.DEFENSE, 1, topicId, null, null,
                        Instant.now().plusSeconds(600), RoleType.DIRECTOR, "DIRECTOR"));
        return issued.command();
    }

    private ClaimService.ClaimSubmission submission(Review review, IdempotencyKey key, List<EvidenceId> evidenceIds) {
        return submission(review, key, ClaimPosition.OPPOSE, "authentication", evidenceIds, null);
    }

    private ClaimService.ClaimSubmission submission(
            Review review, IdempotencyKey key, ClaimPosition position, String subjectKey) {
        return submission(review, key, position, subjectKey, List.of(), null);
    }

    private ClaimService.ClaimSubmission defenseSubmission(
            Review review, IdempotencyKey key, ClaimPosition position, String subjectKey, CommandId commandId) {
        return submission(review, key, position, subjectKey, List.of(), commandId);
    }

    private ClaimService.ClaimSubmission submission(
            Review review,
            IdempotencyKey key,
            ClaimPosition position,
            String subjectKey,
            List<EvidenceId> evidenceIds,
            CommandId commandId) {
        return new ClaimService.ClaimSubmission(
                new ReviewCommandMetadata(review.id(), review.version(), key),
                RoleType.PRODUCT,
                subjectKey,
                ClaimSeverity.P0,
                position,
                position == ClaimPosition.SUPPORT
                        ? "Authentication flow is covered by an explicit refresh policy."
                        : "Authentication flow lacks an explicit token refresh policy.",
                position == ClaimPosition.SUPPORT
                        ? "The requirement defines refresh behavior."
                        : "The requirement does not define refresh behavior.",
                evidenceIds,
                commandId);
    }
}
