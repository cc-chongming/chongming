package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies first-review completion advances only after all mandatory roles have explicitly finished.
 *
 * @author wangli
 */
class InitialReviewProgressServiceTests {

    @Test
    void advancesToConflictDetectionOnlyAfterFourCoreRolesComplete() {
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.INITIAL_REVIEW, 1, 0,
                List.of(
                        new RoleActivation(RoleType.PRODUCT, "product", false),
                        new RoleActivation(RoleType.PROJECT, "project", false),
                        new RoleActivation(RoleType.FRONTEND, "frontend", false),
                        new RoleActivation(RoleType.BACKEND, "backend", false)),
                java.util.Map.of());
        List<ReviewEventDraft> events = new ArrayList<>();
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add);

        complete(service, review, RoleType.PRODUCT, "call-1");
        complete(service, review, RoleType.PROJECT, "call-2");
        complete(service, review, RoleType.FRONTEND, "call-3");

        assertThat(review.stage()).isEqualTo(ReviewStage.INITIAL_REVIEW);

        complete(service, review, RoleType.BACKEND, "call-4");

        assertThat(review.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.ROLE_COMPLETED, ReviewEventType.ROLE_COMPLETED,
                        ReviewEventType.ROLE_COMPLETED, ReviewEventType.ROLE_COMPLETED,
                        ReviewEventType.INITIAL_REVIEW_COMPLETED);

        InitialReviewProgressService.CompletionResult replay = service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("call-4")),
                RoleType.BACKEND, "same completion");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
    }

    @Test
    void failsInitialReviewWhenAnActivatedRoleEndsWithoutExplicitCompletion() {
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.INITIAL_REVIEW, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", false)), java.util.Map.of());
        List<ReviewEventDraft> events = new ArrayList<>();
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add);

        boolean failed = service.failIncompleteRole(
                review, RoleType.PRODUCT, "The role ended without calling complete_initial_review.");

        assertThat(failed).isTrue();
        assertThat(review.stage()).isEqualTo(ReviewStage.FAILED);
        assertThat(review.roleActivations()).singleElement().satisfies(activation ->
                assertThat(activation.initialReviewCompleted()).isFalse());
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.ROLE_FAILED, ReviewEventType.REVIEW_FAILED);
    }

    @Test
    void doesNotFailRoleThatAlreadyCompletedItsInitialReview() {
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.INITIAL_REVIEW, 1, 0,
                List.of(
                        new RoleActivation(RoleType.PRODUCT, "product", true),
                        new RoleActivation(RoleType.PROJECT, "project", false)),
                java.util.Map.of());
        List<ReviewEventDraft> events = new ArrayList<>();
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add);

        boolean failed = service.failIncompleteRole(
                review, RoleType.PRODUCT, "The role ended without calling complete_initial_review.");

        assertThat(failed).isFalse();
        assertThat(review.stage()).isEqualTo(ReviewStage.INITIAL_REVIEW);
        assertThat(events).isEmpty();
    }

    @Test
    void doesNotFailNewAttemptFromLatePreviousAttemptRoleStream() {
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.INITIAL_REVIEW, 2, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", false)), java.util.Map.of());
        List<ReviewEventDraft> events = new ArrayList<>();
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add);

        boolean failed = service.failIncompleteRole(
                review, 1, RoleType.PRODUCT, "The role ended without calling complete_initial_review.");

        assertThat(failed).isFalse();
        assertThat(review.stage()).isEqualTo(ReviewStage.INITIAL_REVIEW);
        assertThat(events).isEmpty();
    }

    @Test
    void treatsLateCompletionFromAlreadyCompletedRoleAsIdempotentSuccessOutsideInitialReview() {
        // PRODUCT finished initial review earlier (e.g. via a Claim submission); the review has moved
        // on to CONFLICT_DETECTION. A late complete_initial_review with a fresh idempotency key must
        // succeed without throwing, or the role agent loops on rejections for tens of minutes.
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.CONFLICT_DETECTION, 1, 0,
                List.of(
                        new RoleActivation(RoleType.PRODUCT, "product", true),
                        new RoleActivation(RoleType.PROJECT, "project", true),
                        new RoleActivation(RoleType.FRONTEND, "frontend", true),
                        new RoleActivation(RoleType.BACKEND, "backend", true)),
                java.util.Map.of());
        List<ReviewEventDraft> events = new ArrayList<>();
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add);

        InitialReviewProgressService.CompletionResult result = service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("late-call")),
                RoleType.PRODUCT, "late completion");

        assertThat(result.replayed()).isTrue();
        assertThat(result.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
        assertThat(review.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
        assertThat(review.roleActivations()).filteredOn(activation -> activation.roleType() == RoleType.PRODUCT)
                .singleElement().satisfies(activation -> assertThat(activation.initialReviewCompleted()).isTrue());
        assertThat(events).isEmpty();
    }

    @Test
    void stillRejectsCompletionFromIncompleteRoleOutsideInitialReview() {
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.CONFLICT_DETECTION, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", false)), java.util.Map.of());
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), ignored -> {});

        assertThatThrownBy(() -> service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("late-call")),
                RoleType.PRODUCT, "late"))
                .isInstanceOf(ai.cc.chongming.review.domain.exception.ReviewDomainException.class);
    }

    private void complete(InitialReviewProgressService service, Review review, RoleType roleType, String key) {
        service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key)), roleType,
                "No blocking finding for " + roleType);
    }

    /**
     * [AIREVIEW-PLAN-024#方案1] Verification-matrix row “检查点完整性”: completion is rejected with
     * ASSESSMENT_COVERAGE_INCOMPLETE and the missing stable keys while any required checkpoint
     * lacks a persisted assessment; a publicSummary can never bypass the guard.
     */
    @Test
    void rejectsCompletionWhileRequiredCheckpointAssessmentsAreMissing() {
        AssessmentService assessmentService = new AssessmentService(
                new InMemoryReviewAssessmentStore(),
                new RolePackRegistry(new PathMatchingResourcePatternResolver()));
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.INITIAL_REVIEW, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", false)), java.util.Map.of());
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), ignored -> {}, assessmentService);

        assertThatThrownBy(() -> service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("bypass-attempt")),
                RoleType.PRODUCT, "一段试图绕过覆盖检查的长摘要"))
                .isInstanceOf(ReviewDomainException.class)
                .satisfies(exception -> {
                    ReviewDomainException domainException = (ReviewDomainException) exception;
                    assertThat(domainException.errorCode()).isEqualTo(ReviewErrorCode.ASSESSMENT_COVERAGE_INCOMPLETE);
                    assertThat(domainException.getMessage())
                            .contains("product.requirement_completeness")
                            .contains("product.acceptance_criteria")
                            .contains("product.user_value")
                            .contains("product.scope_boundary")
                            .contains("product.testability");
                });
        assertThat(review.stage()).isEqualTo(ReviewStage.INITIAL_REVIEW);
        assertThat(review.roleActivations()).singleElement().satisfies(activation ->
                assertThat(activation.initialReviewCompleted()).isFalse());
    }

    /**
     * [AIREVIEW-PLAN-024#方案1] Once every required checkpoint owns exactly one current assessment,
     * completion succeeds and a repeated completion stays idempotent.
     */
    @Test
    void completesIdempotentlyAfterAllRequiredCheckpointAssessmentsAreSubmitted() {
        AssessmentService assessmentService = new AssessmentService(
                new InMemoryReviewAssessmentStore(),
                new RolePackRegistry(new PathMatchingResourcePatternResolver()));
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.INITIAL_REVIEW, 1, 0,
                coreActivations(RoleType.PRODUCT), java.util.Map.of());
        List<ReviewEventDraft> events = new ArrayList<>();
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add, assessmentService);
        for (String checkpointKey : List.of("product.requirement_completeness", "product.acceptance_criteria",
                "product.user_value", "product.scope_boundary", "product.testability",
                "product.adversarial_scrutiny", "product.core_value_stance", "product.recognized_strengths")) {
            assessmentService.submit(review, new AssessmentService.AssessmentSubmission(
                    new ReviewCommandMetadata(review.id(), review.version(),
                            new IdempotencyKey("assessment-" + checkpointKey)),
                    RoleType.PRODUCT, checkpointKey, AssessmentStatus.CONFIRMED, "已确认 " + checkpointKey,
                    null, List.of()));
        }

        InitialReviewProgressService.CompletionResult result = service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("complete-1")),
                RoleType.PRODUCT, "PRODUCT 初审完成");

        assertThat(result.replayed()).isFalse();
        assertThat(review.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.ROLE_COMPLETED, ReviewEventType.INITIAL_REVIEW_COMPLETED);

        InitialReviewProgressService.CompletionResult replay = service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("complete-1")),
                RoleType.PRODUCT, "PRODUCT 初审完成");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
    }

    /**
     * The stage transition requires every core role to have finished its initial review, so the
     * other three core roles are pre-activated as already completed while PRODUCT runs the
     * assessment submission flow under test.
     */
    private static List<RoleActivation> coreActivations(RoleType roleUnderTest) {
        List<RoleActivation> activations = new ArrayList<>();
        for (RoleType coreRole : List.of(
                RoleType.PRODUCT, RoleType.PROJECT, RoleType.FRONTEND, RoleType.BACKEND)) {
            activations.add(new RoleActivation(coreRole, coreRole.name().toLowerCase(), coreRole != roleUnderTest));
        }
        return activations;
    }
}
