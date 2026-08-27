package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchCommandStatus;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.dispatch.InMemoryReviewDispatchStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-024#方案3] Verifies directed dispatch commands are validated before persistence,
 * idempotent on issuance, consumable exactly once by the addressed role, and that the model can
 * never widen its own authority (round, action, recipient).
 *
 * @author wangli
 */
class ReviewDispatchServiceTests {

    private final InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
    private final InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
    private final List<ReviewEventDraft> published = new ArrayList<>();
    private final ReviewDispatchService service = new ReviewDispatchService(dispatchStore, debateStore, published::add);

    @Test
    void issuesChallengeDispatchAfterValidationAndPublishesIssuedEvent() {
        Fixture fixture = openChallengeFixture();

        ReviewDispatchService.DispatchIssueResult result = service.issue(fixture.review(),
                challengeProposal(fixture, key("director-call-1")));

        assertThat(result.replayed()).isFalse();
        ReviewDispatchCommand command = result.command();
        assertThat(command.status()).isEqualTo(DispatchCommandStatus.PENDING);
        assertThat(command.reviewId()).isEqualTo(fixture.review().id());
        assertThat(command.attemptNo()).isEqualTo(fixture.review().attemptNo());
        assertThat(command.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);
        assertThat(command.round()).isEqualTo(1);
        assertThat(command.recipientRole()).isEqualTo(RoleType.PRODUCT);
        assertThat(command.allowedAction()).isEqualTo(DispatchedAction.CHALLENGE);
        assertThat(command.topicId()).isEqualTo(fixture.topic().id());
        assertThat(command.targetClaimId()).isEqualTo(fixture.claim().claimId());
        assertThat(dispatchStore.findById(fixture.review().id(), command.commandId())).contains(command);
        assertThat(published)
                .extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.DISPATCH_COMMAND_ISSUED);
    }

    @Test
    void replayingTheSameIdempotencyKeyReturnsTheOriginalCommand() {
        Fixture fixture = openChallengeFixture();
        IdempotencyKey key = key("director-call-duplicate");

        ReviewDispatchService.DispatchIssueResult first = service.issue(fixture.review(), challengeProposal(fixture, key));
        ReviewDispatchService.DispatchIssueResult replay = service.issue(fixture.review(), challengeProposal(fixture, key));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.command()).isEqualTo(first.command());
        assertThat(dispatchStore.findByReview(fixture.review().id(), fixture.review().attemptNo())).hasSize(1);
    }

    @Test
    void replayingTheSameDispatchIntentWithADifferentKeyReturnsThePendingCommandAndRefreshesExpiry() {
        Fixture fixture = openChallengeFixture();
        IdempotencyKey firstKey = key("director-call-intent-1");
        IdempotencyKey secondKey = key("director-call-intent-2");

        ReviewDispatchService.DispatchIssueResult first =
                service.issue(fixture.review(), challengeProposal(fixture, firstKey));
        ReviewDispatchService.DispatchProposal restated = challengeProposal(fixture, secondKey);
        ReviewDispatchService.DispatchIssueResult duplicate =
                service.issue(fixture.review(), restated);

        assertThat(first.replayed()).isFalse();
        assertThat(duplicate.replayed()).isTrue();
        assertThat(duplicate.command().commandId()).isEqualTo(first.command().commandId());
        // [AIREVIEW-PLAN-033#5] A re-dispatch restates live Director intent: the pending command's
        // expiry is refreshed to the restated proposal's expiry instead of silently counting down.
        assertThat(duplicate.command().expiresAt()).isEqualTo(restated.expiresAt());
        assertThat(dispatchStore.findByReview(fixture.review().id(), fixture.review().attemptNo()))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.commandId()).isEqualTo(first.command().commandId());
                    assertThat(stored.expiresAt()).isEqualTo(restated.expiresAt());
                });
    }

    @Test
    void rejectsDispatchTargetingDirectorOrUnactivatedRoles() {
        Fixture fixture = openChallengeFixture();

        assertThatThrownBy(() -> service.issue(fixture.review(), new ReviewDispatchService.DispatchProposal(
                metadata(fixture.review()), RoleType.DIRECTOR, DispatchedAction.CHALLENGE, 1,
                fixture.topic().id(), fixture.claim().claimId(), null, later(), RoleType.DIRECTOR, "DIRECTOR")))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.UNAUTHORIZED_ROLE);

        assertThatThrownBy(() -> service.issue(fixture.review(), new ReviewDispatchService.DispatchProposal(
                metadata(fixture.review()), RoleType.FRONTEND, DispatchedAction.CHALLENGE, 1,
                fixture.topic().id(), fixture.claim().claimId(), null, later(), RoleType.DIRECTOR, "DIRECTOR")))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.UNAUTHORIZED_ROLE);
        assertThat(dispatchStore.findByReview(fixture.review().id(), fixture.review().attemptNo())).isEmpty();
    }

    @Test
    void rejectsDispatchWhenRoundDoesNotMatchTheCurrentStage() {
        Fixture fixture = openChallengeFixture();

        assertThatThrownBy(() -> service.issue(fixture.review(), new ReviewDispatchService.DispatchProposal(
                metadata(fixture.review()), RoleType.PRODUCT, DispatchedAction.CHALLENGE, 2,
                fixture.topic().id(), fixture.claim().claimId(), null, later(), RoleType.DIRECTOR, "DIRECTOR")))
                .isInstanceOf(ReviewDomainException.class)
                .satisfies(exception -> {
                    assertThat(((ReviewDomainException) exception).errorCode())
                            .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
                    assertThat(exception.getMessage()).contains("does not match the current review stage");
                });
    }

    @Test
    void rejectsDispatchOutsideAnActiveDebateRound() {
        Fixture fixture = openChallengeFixture();
        Review judgingReview = Review.restore(fixture.review().id(), ReviewStage.JUDGING, 1,
                fixture.review().version(), fixture.review().roleActivations(), Map.of());

        assertThatThrownBy(() -> service.issue(judgingReview, challengeProposal(fixture, key("judge-stage"))))
                .isInstanceOf(ReviewDomainException.class)
                .satisfies(exception -> {
                    assertThat(((ReviewDomainException) exception).errorCode())
                            .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
                    assertThat(exception.getMessage()).contains("only during an active debate round");
                });
    }

    @Test
    void rejectsRebuttalDispatchWhileTopicIsNotChallengedAndNamesApplicableActions() {
        Fixture fixture = openChallengeFixture();
        DebateTurn existingTurn = new DebateTurn(new TurnId(UUID.randomUUID()), fixture.topic().id(), 1,
                RoleType.BACKEND, RoleType.PRODUCT, DebateTurnType.CHALLENGE, fixture.claim().claimId(),
                null, "已有回合", List.of(), null, null, Instant.now());
        debateStore.saveTurn(fixture.review().id(), existingTurn);

        assertThatThrownBy(() -> service.issue(fixture.review(), new ReviewDispatchService.DispatchProposal(
                metadata(fixture.review()), RoleType.BACKEND, DispatchedAction.REBUTTAL, 1,
                fixture.topic().id(), null, existingTurn.turnId(), later(), RoleType.DIRECTOR, "DIRECTOR")))
                .isInstanceOf(ReviewDomainException.class)
                .satisfies(exception -> {
                    assertThat(((ReviewDomainException) exception).errorCode())
                            .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
                    assertThat(exception.getMessage()).contains("applicable actions: CHALLENGE, DEFENSE, POSITION_CHANGE, EVIDENCE_REQUEST");
                });
    }

    @Test
    void rejectsDispatchTargetingClaimOutsideTheTopic() {
        Fixture fixture = openChallengeFixture();
        ClaimId foreignClaim = new ClaimId(UUID.randomUUID());

        assertThatThrownBy(() -> service.issue(fixture.review(), new ReviewDispatchService.DispatchProposal(
                metadata(fixture.review()), RoleType.PRODUCT, DispatchedAction.CHALLENGE, 1,
                fixture.topic().id(), foreignClaim, null, later(), RoleType.DIRECTOR, "DIRECTOR")))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.TARGET_CLAIM_REQUIRED);
    }

    @Test
    void rejectsDispatchWithPastExpiry() {
        Fixture fixture = openChallengeFixture();

        assertThatThrownBy(() -> service.issue(fixture.review(), new ReviewDispatchService.DispatchProposal(
                metadata(fixture.review()), RoleType.PRODUCT, DispatchedAction.CHALLENGE, 1,
                fixture.topic().id(), fixture.claim().claimId(), null,
                Instant.now().minusSeconds(10), RoleType.DIRECTOR, "DIRECTOR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");
    }

    @Test
    void resolveForWriteReturnsTheCommandAndConsumeMarksItConsumedIdempotently() {
        Fixture fixture = openChallengeFixture();
        ReviewDispatchCommand command = service.issue(fixture.review(),
                challengeProposal(fixture, key("resolve-happy"))).command();

        ReviewDispatchCommand resolved = service.resolveForWrite(
                fixture.review(), RoleType.PRODUCT, command.commandId(), DispatchedAction.CHALLENGE);
        assertThat(resolved).isEqualTo(command);

        service.consume(fixture.review(), command);
        service.consume(fixture.review(), command);
        assertThat(dispatchStore.findById(fixture.review().id(), command.commandId()))
                .get()
                .extracting(ReviewDispatchCommand::status)
                .isEqualTo(DispatchCommandStatus.CONSUMED);
        assertThat(published)
                .extracting(ReviewEventDraft::type)
                .contains(ReviewEventType.DISPATCH_COMMAND_CONSUMED);
    }

    @Test
    void resolveForWriteRejectsAnotherActorWithRecoverableError() {
        Fixture fixture = openChallengeFixture();
        ReviewDispatchCommand command = service.issue(fixture.review(),
                challengeProposal(fixture, key("resolve-actor"))).command();

        assertThatThrownBy(() -> service.resolveForWrite(
                fixture.review(), RoleType.BACKEND, command.commandId(), DispatchedAction.CHALLENGE))
                .isInstanceOf(ReviewDomainException.class)
                .satisfies(exception -> {
                    assertThat(((ReviewDomainException) exception).errorCode())
                            .isEqualTo(ReviewErrorCode.DISPATCH_ACTOR_MISMATCH);
                    assertThat(exception.getMessage()).contains("addressed to PRODUCT");
                });
        // The envelope stays intact for the real recipient.
        assertThat(dispatchStore.findById(fixture.review().id(), command.commandId()))
                .get()
                .extracting(ReviewDispatchCommand::status)
                .isEqualTo(DispatchCommandStatus.PENDING);
    }

    @Test
    void resolveForWriteRejectsAWiderActionThanTheEnvelopeAllows() {
        Fixture fixture = openChallengeFixture();
        ReviewDispatchCommand command = service.issue(fixture.review(),
                challengeProposal(fixture, key("resolve-action"))).command();

        assertThatThrownBy(() -> service.resolveForWrite(
                fixture.review(), RoleType.PRODUCT, command.commandId(), DispatchedAction.REBUTTAL))
                .isInstanceOf(ReviewDomainException.class)
                .satisfies(exception -> {
                    assertThat(((ReviewDomainException) exception).errorCode())
                            .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
                    assertThat(exception.getMessage()).contains("allows CHALLENGE, not REBUTTAL");
                });
    }

    @Test
    void resolveForWriteExpiresAStaleCommandAndPublishesTheExpiredEvent() {
        Fixture fixture = openChallengeFixture();
        ReviewDispatchCommand stale = new ReviewDispatchCommand(
                new CommandId(UUID.randomUUID()), fixture.review().id(), fixture.review().attemptNo(),
                ReviewStage.DEBATE_ROUND_1, 1, RoleType.PRODUCT, DispatchedAction.CHALLENGE,
                fixture.topic().id(), fixture.claim().claimId(), null,
                Instant.now().minusSeconds(5), DispatchCommandStatus.PENDING,
                key("stale-command"), Instant.now().minusSeconds(60));
        dispatchStore.save(stale);

        assertThatThrownBy(() -> service.resolveForWrite(
                fixture.review(), RoleType.PRODUCT, stale.commandId(), DispatchedAction.CHALLENGE))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.DISPATCH_COMMAND_EXPIRED);
        assertThat(dispatchStore.findById(fixture.review().id(), stale.commandId()))
                .get()
                .extracting(ReviewDispatchCommand::status)
                .isEqualTo(DispatchCommandStatus.EXPIRED);
        assertThat(published)
                .extracting(ReviewEventDraft::type)
                .contains(ReviewEventType.DISPATCH_COMMAND_EXPIRED);
    }

    @Test
    void resolveForWriteNamesLegalPendingActionsWhenTheCommandIsMissing() {
        Fixture fixture = openChallengeFixture();
        ReviewDispatchCommand valid = service.issue(fixture.review(),
                challengeProposal(fixture, key("legal-actions"))).command();

        assertThatThrownBy(() -> service.resolveForWrite(fixture.review(), RoleType.PRODUCT,
                new CommandId(UUID.randomUUID()), DispatchedAction.CHALLENGE))
                .isInstanceOf(ReviewDomainException.class)
                .satisfies(exception -> {
                    assertThat(((ReviewDomainException) exception).errorCode())
                            .isEqualTo(ReviewErrorCode.DISPATCH_COMMAND_REQUIRED);
                    assertThat(exception.getMessage())
                            .contains("legal pending actions")
                            .contains(valid.commandId().value().toString());
                });
    }

    @Test
    void resolveForWriteRejectsCommandsWhoseRoundDriftedWithTheStage() {
        Fixture fixture = openChallengeFixture();
        ReviewDispatchCommand command = service.issue(fixture.review(),
                challengeProposal(fixture, key("round-drift"))).command();
        Review roundTwoReview = Review.restore(fixture.review().id(), ReviewStage.DEBATE_ROUND_2, 1,
                fixture.review().version(), fixture.review().roleActivations(), Map.of());

        assertThatThrownBy(() -> service.resolveForWrite(
                roundTwoReview, RoleType.PRODUCT, command.commandId(), DispatchedAction.CHALLENGE))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);
        assertThat(dispatchStore.findById(fixture.review().id(), command.commandId()))
                .get()
                .extracting(ReviewDispatchCommand::status)
                .isEqualTo(DispatchCommandStatus.REJECTED);
        assertThat(published)
                .extracting(ReviewEventDraft::type)
                .contains(ReviewEventType.DISPATCH_COMMAND_REJECTED);
    }

    @Test
    void rejectAllPendingMarksEveryPendingCommandRejectedAndKeepsTerminalOnes() {
        Fixture fixture = openChallengeFixture();
        ReviewDispatchCommand pending = service.issue(fixture.review(),
                challengeProposal(fixture, key("pending-one"))).command();
        // A different recipient so the content-level dedup keeps two distinct envelopes; both stay
        // addressed to activated roles on the same topic.
        ReviewDispatchCommand consumed = service.issue(fixture.review(),
                new ReviewDispatchService.DispatchProposal(
                        metadata(fixture.review()), RoleType.BACKEND, DispatchedAction.CHALLENGE, 1,
                        fixture.topic().id(), fixture.claim().claimId(), null,
                        later(), RoleType.DIRECTOR, "DIRECTOR")).command();
        service.consume(fixture.review(), consumed);

        service.rejectAllPending(fixture.review(), "JUDGING_STARTED");

        assertThat(dispatchStore.findById(fixture.review().id(), pending.commandId()))
                .get().extracting(ReviewDispatchCommand::status).isEqualTo(DispatchCommandStatus.REJECTED);
        assertThat(dispatchStore.findById(fixture.review().id(), consumed.commandId()))
                .get().extracting(ReviewDispatchCommand::status).isEqualTo(DispatchCommandStatus.CONSUMED);
    }

    @Test
    void envelopeTextNamesExactlyTheOneAuthorizedWriteAction() {
        Fixture fixture = openChallengeFixture();
        ReviewDispatchCommand command = service.issue(fixture.review(),
                challengeProposal(fixture, key("envelope"))).command();

        String envelope = ReviewDispatchService.envelopeText(command);

        assertThat(envelope)
                .contains("commandId=" + command.commandId().value())
                .contains("allowedAction=CHALLENGE")
                .contains("targetClaimId=" + fixture.claim().claimId().value())
                .contains("exactly this one write action");
    }

    // --- fixtures -----------------------------------------------------------

    private record Fixture(Review review, DebateTopic topic, Claim claim) {
    }

    private Fixture openChallengeFixture() {
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.DEBATE_ROUND_1, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", true),
                        new RoleActivation(RoleType.BACKEND, "backend", true)),
                Map.of());
        Claim claim = new Claim(new ClaimId(UUID.randomUUID()), review.id(), RoleType.BACKEND,
                "api.contract", ClaimSeverity.P1, ClaimPosition.OPPOSE, "接口契约与需求冲突", "字段定义不一致", List.of());
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), review.id(),
                "api.contract", List.of(claim.claimId()));
        debateStore.saveClaim(claim);
        debateStore.saveTopic(topic);
        return new Fixture(review, topic, claim);
    }

    private ReviewDispatchService.DispatchProposal challengeProposal(Fixture fixture, IdempotencyKey key) {
        return new ReviewDispatchService.DispatchProposal(
                new ReviewCommandMetadata(fixture.review().id(), fixture.review().version(), key),
                RoleType.PRODUCT, DispatchedAction.CHALLENGE, 1,
                fixture.topic().id(), fixture.claim().claimId(), null,
                later(), RoleType.DIRECTOR, "DIRECTOR");
    }

    private static IdempotencyKey key(String suffix) {
        return new IdempotencyKey("tool:" + suffix);
    }

    private static ReviewCommandMetadata metadata(Review review) {
        return new ReviewCommandMetadata(review.id(), review.version(), key("call-" + UUID.randomUUID()));
    }

    private static Instant later() {
        return Instant.now().plusSeconds(600);
    }
}
