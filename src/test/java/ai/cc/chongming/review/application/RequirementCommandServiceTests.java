package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.ReviewRequirementLinkStore;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.Permission;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementRepository;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRequirementLinkStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-021#2] Verifies requirement commands retain creator identity and bind reviews explicitly.
 *
 * @author zyj
 */
class RequirementCommandServiceTests {

    @Test
    void createsDraftForCurrentReviewerThenBindsItForReview() {
        RequirementRepository repository = new InMemoryRequirementRepository();
        ReviewerIdentityProvider identityProvider = () -> new ReviewerIdentity("product-owner", Set.of(Permission.REVIEW));
        AtomicReference<ReviewId> linkedReview = new AtomicReference<>();
        AtomicReference<ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId> linkedRequirement = new AtomicReference<>();
        ReviewRequirementLinkStore linkStore = (reviewId, requirementId) -> {
            linkedReview.set(reviewId);
            linkedRequirement.set(requirementId);
            return true;
        };
        RequirementCommandService service = new RequirementCommandService(repository, identityProvider, linkStore);

        Requirement created = service.create(new RequirementCommandService.CreateRequirementCommand(
                "学生身份同步", "# 目标\n\n同步基础身份字段。", "delivery-owner", "cx-ai", "P1"));
        Requirement submitted = service.submitForReview(created.id(), new ReviewId(UUID.randomUUID()), created.version());

        assertThat(created.creatorId()).isEqualTo("product-owner");
        assertThat(submitted.status()).isEqualTo(RequirementStatus.PENDING_REVIEW);
        assertThat(repository.findByReviewId(submitted.reviewId())).contains(submitted);
        assertThat(linkedReview.get()).isEqualTo(submitted.reviewId());
        assertThat(linkedRequirement.get()).isEqualTo(submitted.id());
    }

    @Test
    void keepsRequirementDraftWhenReviewReservationIsRejected() {
        RequirementRepository repository = new InMemoryRequirementRepository();
        ReviewerIdentityProvider identityProvider = () -> new ReviewerIdentity("product-owner", Set.of(Permission.REVIEW));
        RequirementCommandService service = new RequirementCommandService(repository, identityProvider,
                (reviewId, requirementId) -> false);
        Requirement created = service.create(new RequirementCommandService.CreateRequirementCommand(
                "学生身份同步", "# 目标", null, "cx-ai", "P1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.submitForReview(created.id(), new ReviewId(UUID.randomUUID()), created.version()))
                .isInstanceOf(ai.cc.chongming.review.domain.exception.RequirementDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ai.cc.chongming.review.domain.exception.RequirementErrorCode.REVIEW_ALREADY_BOUND);

        assertThat(created.status()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(created.reviewId()).isNull();
    }

    @Test
    void permanentlyDeletesRequirementAtItsCurrentVersion() {
        RequirementRepository repository = new InMemoryRequirementRepository();
        ReviewerIdentityProvider identityProvider = () -> new ReviewerIdentity("product-owner", Set.of(Permission.REVIEW));
        RequirementCommandService service = new RequirementCommandService(repository, identityProvider);
        Requirement created = service.create(new RequirementCommandService.CreateRequirementCommand(
                "过期草稿", "# 草稿", null, "cx-ai", "P1"));

        service.delete(created.id(), created.version());

        assertThat(repository.findById(created.id())).isEmpty();
    }

    @Test
    void deletesReviewBoundRequirementAndReleasesItsReverseLink() {
        RequirementRepository repository = new InMemoryRequirementRepository();
        ReviewerIdentityProvider identityProvider = () -> new ReviewerIdentity("product-owner", Set.of(Permission.REVIEW));
        AtomicReference<RequirementId> unlinkedRequirement = new AtomicReference<>();
        ReviewRequirementLinkStore linkStore = new ReviewRequirementLinkStore() {
            @Override
            public boolean tryBindPendingReview(ReviewId reviewId, RequirementId requirementId) {
                return true;
            }

            @Override
            public void unlinkRequirement(RequirementId requirementId) {
                unlinkedRequirement.set(requirementId);
            }
        };
        RequirementCommandService service = new RequirementCommandService(repository, identityProvider, linkStore);
        Requirement created = service.create(new RequirementCommandService.CreateRequirementCommand(
                "已绑定草稿", "# 草稿", null, "cx-ai", "P1"));
        Requirement submitted = service.submitForReview(created.id(), new ReviewId(UUID.randomUUID()), created.version());

        service.delete(submitted.id(), submitted.version());

        assertThat(repository.findById(submitted.id())).isEmpty();
        assertThat(unlinkedRequirement).hasValue(submitted.id());
    }

    @Test
    void bindsAtMostOnePendingReviewWhenTheSameRequirementIsSubmittedConcurrently() throws Exception {
        RequirementRepository repository = new InMemoryRequirementRepository();
        ReviewerIdentityProvider identityProvider = () -> new ReviewerIdentity("product-owner", Set.of(Permission.REVIEW));
        InMemoryReviewRegistry reviewRegistry = new InMemoryReviewRegistry();
        ReviewId firstReviewId = new ReviewId(UUID.randomUUID());
        ReviewId secondReviewId = new ReviewId(UUID.randomUUID());
        registerPending(reviewRegistry, firstReviewId);
        registerPending(reviewRegistry, secondReviewId);
        InMemoryReviewRequirementLinkStore delegate = new InMemoryReviewRequirementLinkStore(reviewRegistry);
        RequirementCommandService service = new RequirementCommandService(
                repository, identityProvider, new CoordinatedReviewRequirementLinkStore(delegate));
        Requirement created = service.create(new RequirementCommandService.CreateRequirementCommand(
                "学生身份同步", "# 目标", null, "cx-ai", "P1"));
        long expectedVersion = created.version();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<SubmissionResult> first = executor.submit(() -> submit(
                    service, created.id(), expectedVersion, firstReviewId, start));
            Future<SubmissionResult> second = executor.submit(() -> submit(
                    service, created.id(), expectedVersion, secondReviewId, start));
            start.countDown();

            List<SubmissionResult> results = List.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            SubmissionResult rejected = results.stream().filter(result -> !result.succeeded()).findFirst().orElseThrow();

            assertThat(results).filteredOn(SubmissionResult::succeeded).hasSize(1);
            assertThat(delegate.tryBindPendingReview(rejected.reviewId(), new RequirementId(UUID.randomUUID()))).isTrue();
            assertThat(repository.findById(created.id())).get().satisfies(requirement -> {
                assertThat(requirement.status()).isEqualTo(RequirementStatus.PENDING_REVIEW);
                assertThat(requirement.reviewId()).isNotEqualTo(rejected.reviewId());
            });
        }
    }

    private SubmissionResult submit(
            RequirementCommandService service,
            RequirementId requirementId,
            long expectedVersion,
            ReviewId reviewId,
            CountDownLatch start) throws Exception {
        start.await(1, TimeUnit.SECONDS);
        try {
            service.submitForReview(requirementId, reviewId, expectedVersion);
            return new SubmissionResult(reviewId, true);
        } catch (ai.cc.chongming.review.domain.exception.RequirementDomainException exception) {
            return new SubmissionResult(reviewId, false);
        }
    }

    private void registerPending(InMemoryReviewRegistry registry, ReviewId reviewId) {
        registry.register(Review.restore(reviewId, ReviewStage.PENDING, 1, 0L, List.of(), Map.of()));
    }

    private record SubmissionResult(ReviewId reviewId, boolean succeeded) {
    }

    private static final class CoordinatedReviewRequirementLinkStore implements ReviewRequirementLinkStore {

        private final ReviewRequirementLinkStore delegate;
        private final CountDownLatch bindings = new CountDownLatch(2);

        private CoordinatedReviewRequirementLinkStore(ReviewRequirementLinkStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean tryBindPendingReview(ReviewId reviewId, RequirementId requirementId) {
            boolean bound = delegate.tryBindPendingReview(reviewId, requirementId);
            if (!bound) {
                return false;
            }
            bindings.countDown();
            try {
                bindings.await(500, TimeUnit.MILLISECONDS);
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while coordinating review bindings", exception);
            }
        }
    }
}
