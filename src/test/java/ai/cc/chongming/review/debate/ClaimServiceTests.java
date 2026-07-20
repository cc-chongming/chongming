package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.application.ClaimService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies evidence ownership, high-severity normalization and idempotent Claim submission.
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

    private ClaimService.ClaimSubmission submission(Review review, IdempotencyKey key, List<EvidenceId> evidenceIds) {
        return new ClaimService.ClaimSubmission(
                new ReviewCommandMetadata(review.id(), review.version(), key),
                RoleType.PRODUCT,
                "authentication",
                ClaimSeverity.P0,
                ClaimPosition.OPPOSE,
                "Authentication flow lacks an explicit token refresh policy.",
                "The requirement does not define refresh behavior.",
                evidenceIds);
    }
}
