package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-024#方案1] Verifies role assessment submissions are validated against the role
 * checklist, persisted with server-injected identity, idempotent on repeat, and queryable for the
 * required-checkpoint coverage guard.
 *
 * @author wangli
 */
class AssessmentServiceTests {

    private static final List<String> PRODUCT_REQUIRED_KEYS = List.of(
            "product.requirement_completeness",
            "product.acceptance_criteria",
            "product.user_value",
            "product.scope_boundary",
            "product.testability");

    private final InMemoryReviewAssessmentStore store = new InMemoryReviewAssessmentStore();
    private final AssessmentService service = new AssessmentService(
            store, new RolePackRegistry(new PathMatchingResourcePatternResolver()));

    @Test
    void submitsConfirmedAssessmentWithServerInjectedIdentityAndMakesItQueryable() {
        Review review = initialReview(RoleType.PRODUCT);

        AssessmentService.AssessmentSubmissionResult result = service.submit(review, submission(review,
                "product.user_value", AssessmentStatus.CONFIRMED, "用户价值明确", null));

        assertThat(result.replayed()).isFalse();
        assertThat(result.assessment().reviewId()).isEqualTo(review.id());
        assertThat(result.assessment().attemptNo()).isEqualTo(review.attemptNo());
        assertThat(result.assessment().roleType()).isEqualTo(RoleType.PRODUCT);
        assertThat(result.assessment().idempotencyKey()).isEqualTo(ReviewAssessment.idempotencyKeyFor(
                review.id(), review.attemptNo(), RoleType.PRODUCT, "product.user_value"));
        assertThat(service.findByReview(review.id(), review.attemptNo(), RoleType.PRODUCT))
                .singleElement()
                .satisfies(assessment -> {
                    assertThat(assessment.checkpointKey()).isEqualTo("product.user_value");
                    assertThat(assessment.status()).isEqualTo(AssessmentStatus.CONFIRMED);
                });
    }

    @Test
    void keepsRepeatedCheckpointSubmissionsIdempotentWithLatestWinning() {
        Review review = initialReview(RoleType.PRODUCT);

        AssessmentService.AssessmentSubmissionResult first = service.submit(review, submission(review,
                "product.testability", AssessmentStatus.GAP, "初判存在验证缺口", "缺少端到端验证条件"));
        AssessmentService.AssessmentSubmissionResult latest = service.submit(review, submission(review,
                "product.testability", AssessmentStatus.CONFIRMED, "复核后确认可验证", null));

        assertThat(first.replayed()).isFalse();
        assertThat(latest.replayed()).isFalse();
        assertThat(store.findByReview(review.id(), review.attemptNo(), RoleType.PRODUCT)).hasSize(1);
        assertThat(store.findByReview(review.id(), review.attemptNo(), RoleType.PRODUCT).getFirst().status())
                .isEqualTo(AssessmentStatus.CONFIRMED);
    }

    @Test
    void replaysTheSameCommandIdempotencyKeyWithoutRewritingTheStore() {
        Review review = initialReview(RoleType.PRODUCT);
        ReviewCommandMetadata metadata = new ReviewCommandMetadata(
                review.id(), review.version(), new IdempotencyKey("tool-call-1"));

        AssessmentService.AssessmentSubmissionResult first = service.submit(review, new AssessmentService.AssessmentSubmission(
                metadata, RoleType.PRODUCT, "product.user_value", AssessmentStatus.CONFIRMED, "用户价值明确", null, List.of()));
        AssessmentService.AssessmentSubmissionResult replay = service.submit(review, new AssessmentService.AssessmentSubmission(
                metadata, RoleType.PRODUCT, "product.user_value", AssessmentStatus.CONFIRMED, "用户价值明确", null, List.of()));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.assessment()).isEqualTo(first.assessment());
    }

    @Test
    void rejectsCheckpointKeyOutsideTheRoleChecklist() {
        Review review = initialReview(RoleType.PRODUCT);

        assertThatThrownBy(() -> service.submit(review, submission(review,
                "backend.api_contract", AssessmentStatus.CONFIRMED, "越权检查点", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backend.api_contract");
        assertThat(store.findByReview(review.id(), review.attemptNo())).isEmpty();
    }

    @Test
    void rejectsAssessmentsOutsideInitialReviewOrFromUnauthorizedRoles() {
        Review debateReview = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.CONFLICT_DETECTION, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", false)), java.util.Map.of());
        assertThatThrownBy(() -> service.submit(debateReview, submission(debateReview,
                "product.user_value", AssessmentStatus.CONFIRMED, "阶段不允许", null)))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.ILLEGAL_STATE_TRANSITION);

        Review review = initialReview(RoleType.PRODUCT);
        assertThatThrownBy(() -> service.submit(review, submission(review, RoleType.BACKEND,
                "backend.api_contract", AssessmentStatus.CONFIRMED, "非本角色", null)))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(exception -> ((ReviewDomainException) exception).errorCode())
                .isEqualTo(ReviewErrorCode.UNAUTHORIZED_ROLE);
    }

    @Test
    void reportsMissingRequiredCheckpointKeysUntilCoverageIsComplete() {
        Review review = initialReview(RoleType.PRODUCT);

        assertThat(service.missingRequiredCheckpointKeys(review.id(), review.attemptNo(), RoleType.PRODUCT))
                .containsExactlyElementsOf(PRODUCT_REQUIRED_KEYS);
        assertThat(service.isCoverageComplete(review.id(), review.attemptNo(), RoleType.PRODUCT)).isFalse();

        for (String checkpointKey : PRODUCT_REQUIRED_KEYS) {
            service.submit(review, submission(review, checkpointKey, AssessmentStatus.CONFIRMED, "已确认", null));
        }
        // Optional checkpoints stay optional: coverage is complete without product.business_risk.
        assertThat(service.missingRequiredCheckpointKeys(review.id(), review.attemptNo(), RoleType.PRODUCT))
                .isEmpty();
        assertThat(service.isCoverageComplete(review.id(), review.attemptNo(), RoleType.PRODUCT)).isTrue();
    }

    @Test
    void derivesCompletionSummaryFromPersistedAssessmentsAndClaims() {
        Review review = initialReview(RoleType.PRODUCT);
        service.submit(review, submission(review, "product.user_value", AssessmentStatus.CONFIRMED, "用户价值明确", null));
        service.submit(review, submission(review, "product.testability", AssessmentStatus.UNKNOWN,
                "无法确认", "当前评审快照未授予验证脚本"));

        String summary = service.derivedCompletionSummary(
                review.id(), review.attemptNo(), RoleType.PRODUCT, List.of(), "模型补充说明");

        assertThat(summary)
                .contains("CONFIRMED=1")
                .contains("UNKNOWN=1")
                .contains("product.user_value：CONFIRMED 用户价值明确")
                .contains("product.testability：UNKNOWN 无法确认")
                .contains("角色补充：模型补充说明");
    }

    private Review initialReview(RoleType roleType) {
        return Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.INITIAL_REVIEW, 1, 0,
                List.of(new RoleActivation(roleType, roleType.name().toLowerCase(), false)), java.util.Map.of());
    }

    private AssessmentService.AssessmentSubmission submission(
            Review review, String checkpointKey, AssessmentStatus status, String summary, String reasonSummary) {
        return submission(review, RoleType.PRODUCT, checkpointKey, status, summary, reasonSummary);
    }

    private AssessmentService.AssessmentSubmission submission(
            Review review, RoleType actorRole, String checkpointKey, AssessmentStatus status,
            String summary, String reasonSummary) {
        return new AssessmentService.AssessmentSubmission(
                new ReviewCommandMetadata(review.id(), review.version(),
                        new IdempotencyKey("call-" + UUID.randomUUID())),
                actorRole, checkpointKey, status, summary, reasonSummary, List.of());
    }
}
