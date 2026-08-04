package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import ai.cc.chongming.review.infrastructure.report.InMemoryReviewReportStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewPlatformProjectionStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-021#8] Verifies review and report platform list filters.
 *
 * @author zyj
 */
class ReviewListQueryServiceTests {

    @Test
    void filtersLatestReviewProjectionByStageAndReportAvailability() {
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        ReviewId planningReview = new ReviewId(UUID.randomUUID());
        ReviewId completedReview = new ReviewId(UUID.randomUUID());
        register(reviews, planningReview, ReviewStage.PLANNING);
        register(reviews, completedReview, ReviewStage.COMPLETED);
        events.append(draft(planningReview, ReviewStage.PLANNING));
        events.append(draft(completedReview, ReviewStage.COMPLETED));
        reports.append(new ReviewReport(
                UUID.randomUUID(), completedReview, 1L, 1L, "a".repeat(64), "{}", "# 报告", Instant.now()));
        ReviewListQueryService service = new ReviewListQueryService(
                new InMemoryReviewPlatformProjectionStore(events, reports, reviews), reports);

        ReviewListQueryService.ReviewPage completed = service.findPage("completed", true, 1, 20);

        assertThat(completed.total()).isEqualTo(1L);
        assertThat(completed.items()).singleElement().satisfies(view -> {
            assertThat(view.reviewId()).isEqualTo(completedReview.value());
            assertThat(view.hasReport()).isTrue();
            assertThat(view.reportVersion()).isEqualTo(1L);
        });
        assertThat(service.findReports(1, 20).items()).singleElement().extracting(ReviewListQueryService.ReportView::reviewId)
                .isEqualTo(completedReview.value());
    }

    @Test
    void paginatesAfterTheFiveHundredthReviewWithoutTruncatingMatches() {
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        ReviewId expected = new ReviewId(UUID.randomUUID());
        for (int index = 0; index < 500; index++) {
            ReviewId reviewId = new ReviewId(UUID.randomUUID());
            register(reviews, reviewId, ReviewStage.COMPLETED);
            events.append(draft(reviewId, ReviewStage.COMPLETED));
        }
        register(reviews, expected, ReviewStage.PLANNING);
        events.append(draft(expected, ReviewStage.PLANNING));
        ReviewListQueryService service = new ReviewListQueryService(
                new InMemoryReviewPlatformProjectionStore(events, reports, reviews), reports);

        ReviewListQueryService.ReviewPage page = service.findPage("planning", null, 1, 20);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).singleElement().extracting(ReviewListQueryService.ReviewView::reviewId)
                .isEqualTo(expected.value());
    }

    @Test
    void includesAnAcceptedPendingReviewBeforeItHasPublishedAnyRuntimeEvent() {
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        ReviewId pending = new ReviewId(UUID.randomUUID());
        register(reviews, pending, ReviewStage.PENDING);
        ReviewListQueryService service = new ReviewListQueryService(
                new InMemoryReviewPlatformProjectionStore(events, reports, reviews), reports);

        ReviewListQueryService.ReviewPage result = service.findPage("PENDING", false, 1, 20);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).singleElement().satisfies(view -> {
            assertThat(view.reviewId()).isEqualTo(pending.value());
            assertThat(view.lastEventType()).isNull();
            assertThat(view.lastSequence()).isZero();
        });
    }

    @Test
    void defaultsProgressWhenTheLatestPersistedEventPredatesProgressTracking() {
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        ReviewId planning = new ReviewId(UUID.randomUUID());
        register(reviews, planning, ReviewStage.PLANNING);
        events.append(draft(planning, ReviewStage.PLANNING, null));
        ReviewListQueryService service = new ReviewListQueryService(
                new InMemoryReviewPlatformProjectionStore(events, reports, reviews), reports);

        ReviewListQueryService.ReviewPage result = service.findPage("PLANNING", false, 1, 20);

        assertThat(result.items()).singleElement().satisfies(view -> {
            assertThat(view.progress()).isZero();
            assertThat(view.lastEventType()).isEqualTo("PLAN_CREATED");
        });
    }

    @Test
    void returnsOnlyBoundedMetadataForTheReportListPage() {
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        ReviewId first = new ReviewId(UUID.randomUUID());
        ReviewId second = new ReviewId(UUID.randomUUID());
        reports.append(new ReviewReport(
                UUID.randomUUID(), first, 1L, 1L, "a".repeat(64), "{\"body\":\"first\"}",
                "# first", Instant.parse("2026-08-01T08:00:00Z")));
        reports.append(new ReviewReport(
                UUID.randomUUID(), second, 1L, 1L, "b".repeat(64), "{\"body\":\"second\"}",
                "# second", Instant.parse("2026-08-01T08:01:00Z")));
        ReviewListQueryService service = new ReviewListQueryService(
                new InMemoryReviewPlatformProjectionStore(events, reports, reviews), reports);

        ReviewListQueryService.ReportPage page = service.findReports(1, 1);

        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.items()).singleElement().satisfies(report -> {
            assertThat(report.reviewId()).isEqualTo(second.value());
            assertThat(report.contentHash()).isEqualTo("b".repeat(64));
            assertThat(report.createdAt()).isEqualTo("2026-08-01T08:01:00Z");
        });
    }

    @Test
    void keepsMetadataPagingStableWhenReportsShareTheSameCreationTime() {
        InMemoryReviewEventStore events = new InMemoryReviewEventStore();
        InMemoryReviewReportStore reports = new InMemoryReviewReportStore();
        InMemoryReviewRegistry reviews = new InMemoryReviewRegistry();
        ReviewId lowerId = new ReviewId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ReviewId higherId = new ReviewId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        Instant createdAt = Instant.parse("2026-08-01T08:00:00Z");
        reports.append(new ReviewReport(UUID.randomUUID(), lowerId, 1L, 1L, "a".repeat(64), "{}", "# lower", createdAt));
        reports.append(new ReviewReport(UUID.randomUUID(), higherId, 1L, 1L, "b".repeat(64), "{}", "# higher", createdAt));
        ReviewListQueryService service = new ReviewListQueryService(
                new InMemoryReviewPlatformProjectionStore(events, reports, reviews), reports);

        ReviewListQueryService.ReportPage firstPage = service.findReports(1, 1);
        ReviewListQueryService.ReportPage secondPage = service.findReports(2, 1);

        assertThat(firstPage.items()).singleElement().extracting(ReviewListQueryService.ReportView::reviewId)
                .isEqualTo(higherId.value());
        assertThat(secondPage.items()).singleElement().extracting(ReviewListQueryService.ReportView::reviewId)
                .isEqualTo(lowerId.value());
    }

    private void register(InMemoryReviewRegistry registry, ReviewId reviewId, ReviewStage stage) {
        registry.register(Review.restore(reviewId, stage, 1, 0L, List.of(), Map.of()));
    }

    private ReviewEventDraft draft(ReviewId reviewId, ReviewStage stage) {
        return draft(reviewId, stage, 20);
    }

    private ReviewEventDraft draft(ReviewId reviewId, ReviewStage stage, Integer progress) {
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
                Map.of("publicSummary", "平台列表事件"));
    }
}
