package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementVisibility;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementRepository;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewPlatformProjectionStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import ai.cc.chongming.review.infrastructure.report.InMemoryReviewReportStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-021#4] Verifies platform dashboard projection is based on lifecycle and immutable events.
 *
 * @author zyj
 */
class DashboardQueryServiceTests {

    @Test
    void aggregatesRequirementStatusesAndOnlyNonTerminalReviews() {
        InMemoryRequirementRepository requirements = new InMemoryRequirementRepository();
        requirements.save(Requirement.draft(
                new RequirementId(UUID.randomUUID()), "学生身份同步", "需求描述", "alice", null, "cx-ai", "P1"));
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        ReviewId activeReview = new ReviewId(UUID.randomUUID());
        ReviewId completedReview = new ReviewId(UUID.randomUUID());
        reviews.register(Review.restore(activeReview, ReviewStage.INITIAL_REVIEW, 1, 0L, List.of(), Map.of()));
        reviews.register(Review.restore(completedReview, ReviewStage.COMPLETED, 1, 0L, List.of(), Map.of()));
        events.append(draft(activeReview, ReviewStage.INITIAL_REVIEW, "正在初审"));
        events.append(draft(completedReview, ReviewStage.COMPLETED, "评审完成"));

        DashboardQueryService.DashboardView result = new DashboardQueryService(
                requirements, events, new InMemoryReviewPlatformProjectionStore(events, reports, reviews)).getDashboard();

        assertThat(result.requirementStatusCounts()).containsEntry("DRAFT", 1L).containsEntry("DONE", 0L);
        assertThat(result.activeReviewCount()).isEqualTo(1L);
        assertThat(result.activeReviews()).singleElement().extracting(DashboardQueryService.ReviewView::reviewId)
                .isEqualTo(activeReview.value().toString());
        assertThat(result.recentActivities()).hasSize(2);
    }

    @Test
    void countsAndShowsActiveReviewsBeyondTheMostRecentTerminalWindow() {
        InMemoryRequirementRepository requirements = new InMemoryRequirementRepository();
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        for (int index = 0; index < 12; index++) {
            ReviewId terminal = new ReviewId(UUID.randomUUID());
            reviews.register(Review.restore(terminal, ReviewStage.COMPLETED, 1, 1L, List.of(), Map.of()));
            events.append(draft(terminal, ReviewStage.COMPLETED, "已完成-" + index));
        }
        ReviewId active = new ReviewId(UUID.randomUUID());
        reviews.register(Review.restore(active, ReviewStage.INITIAL_REVIEW, 1, 1L, List.of(), Map.of()));
        events.append(draft(active, ReviewStage.INITIAL_REVIEW, "仍在评审"));
        DashboardQueryService service = new DashboardQueryService(
                requirements, events, new InMemoryReviewPlatformProjectionStore(events, reports, reviews));

        DashboardQueryService.DashboardView result = service.getDashboard();

        assertThat(result.activeReviewCount()).isEqualTo(1L);
        assertThat(result.activeReviews()).singleElement()
                .extracting(DashboardQueryService.ReviewView::reviewId)
                .isEqualTo(active.value().toString());
    }

    @Test
    void defaultsProgressForHistoricalEventsThatDoNotContainIt() {
        InMemoryRequirementRepository requirements = new InMemoryRequirementRepository();
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        ReviewId active = new ReviewId(UUID.randomUUID());
        reviews.register(Review.restore(active, ReviewStage.PLANNING, 1, 1L, List.of(), Map.of()));
        events.append(draft(active, ReviewStage.PLANNING, "历史事件", null));
        DashboardQueryService service = new DashboardQueryService(
                requirements, events, new InMemoryReviewPlatformProjectionStore(events, reports, reviews));

        DashboardQueryService.DashboardView result = service.getDashboard();

        assertThat(result.activeReviews()).singleElement()
                .extracting(DashboardQueryService.ReviewView::progress)
                .isEqualTo(0);
    }

    /**
     * [AIREVIEW-PLAN-027] Requirement status counts converge to the viewer's visibility while
     * review projections stay platform-wide; a {@code null} visibility keeps the historical
     * platform-wide totals.
     */
    @Test
    void requirementStatusCountsConvergeToTheViewerVisibility() {
        InMemoryRequirementRepository requirements = new InMemoryRequirementRepository();
        Requirement own = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "自建需求", "描述", "dev-zhang", null, "cx-ai", "P1");
        Requirement assigned = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "指派需求", "描述", "pm-wang", null, "cx-ai", "P1");
        Requirement foreign = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "无关需求", "描述", "pm-wang", null, "cx-ai", "P2");
        requirements.save(own);
        requirements.save(assigned);
        requirements.save(foreign);
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        DashboardQueryService service = new DashboardQueryService(
                requirements, events, new InMemoryReviewPlatformProjectionStore(events, reports, reviews));

        DashboardQueryService.DashboardView platformWide = service.getDashboard();
        assertThat(platformWide.requirementStatusCounts()).containsEntry("DRAFT", 3L);

        DashboardQueryService.DashboardView scoped = service.getDashboard(
                new RequirementVisibility("dev-zhang", Set.of(assigned.id())));
        assertThat(scoped.requirementStatusCounts()).containsEntry("DRAFT", 2L);

        DashboardQueryService.DashboardView creatorOnly = service.getDashboard(
                new RequirementVisibility("dev-zhang", Set.of()));
        assertThat(creatorOnly.requirementStatusCounts()).containsEntry("DRAFT", 1L);
    }

    private ReviewEventDraft draft(ReviewId reviewId, ReviewStage stage, String summary) {
        return draft(reviewId, stage, summary, 30);
    }

    private ReviewEventDraft draft(ReviewId reviewId, ReviewStage stage, String summary, Integer progress) {
        return new ReviewEventDraft(
                reviewId,
                1,
                ReviewEventType.PLAN_CREATED,
                stage,
                null,
                null,
                null,
                null,
                null,
                null,
                progress,
                Instant.now(),
                1,
                Map.of("publicSummary", summary));
    }
}
