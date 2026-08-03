package ai.cc.chongming.review.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewPlatformProjectionStore.ReviewProjectionFilter;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPlatformProjectionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H2] Covers durable platform projections without requiring a running MySQL instance.
 *
 * @author zyj
 */
class MyBatisReviewPlatformProjectionStoreTests {

    @Test
    void mapsTheLatestEventAndMetadataOnlyReportAndPushesFiltersToTheMapper() {
        ReviewPlatformProjectionMapper mapper = mock(ReviewPlatformProjectionMapper.class);
        String reviewId = UUID.randomUUID().toString();
        ReviewPlatformProjectionMapper.PlatformReviewRow row = row(reviewId, "{\"publicSummary\":\"已建立计划\"}", true, true);
        when(mapper.findReviewPage("INITIAL_REVIEW", true, true, 20L, 20)).thenReturn(List.of(row));
        when(mapper.countReviewPage("INITIAL_REVIEW", true, true)).thenReturn(4L);
        MyBatisReviewPlatformProjectionStore store = new MyBatisReviewPlatformProjectionStore(mapper, new ObjectMapper());

        var page = store.findReviewPage(new ReviewProjectionFilter(ReviewStage.INITIAL_REVIEW, true, true), 2, 20);

        assertThat(page.total()).isEqualTo(4L);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.reviewId().value().toString()).isEqualTo(reviewId);
            assertThat(item.latestEvent().type()).isEqualTo(ReviewEventType.PLAN_CREATED);
            assertThat(item.latestEvent().payload()).containsEntry("publicSummary", "已建立计划");
            assertThat(item.latestReport()).extracting(
                    ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::reportVersion,
                    ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::gateVersion)
                    .containsExactly(2L, 1L);
        });
        verify(mapper).findReviewPage("INITIAL_REVIEW", true, true, 20L, 20);
        verify(mapper).countReviewPage("INITIAL_REVIEW", true, true);
    }

    @Test
    void supportsRowsWithoutEventsOrReportsAndNullFilters() {
        ReviewPlatformProjectionMapper mapper = mock(ReviewPlatformProjectionMapper.class);
        String reviewId = UUID.randomUUID().toString();
        when(mapper.findReviewPage(null, null, null, 0L, 10)).thenReturn(List.of(row(reviewId, null, false, false)));
        when(mapper.countReviewPage(null, null, null)).thenReturn(1L);

        var page = new MyBatisReviewPlatformProjectionStore(mapper, new ObjectMapper()).findReviewPage(null, 1, 10);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.latestEvent()).isNull();
            assertThat(item.latestReport()).isNull();
            assertThat(item.stage()).isEqualTo(ReviewStage.PENDING);
        });
    }

    @Test
    void rejectsMalformedPersistedEventPayloadRatherThanPublishingAnAmbiguousProjection() {
        ReviewPlatformProjectionMapper mapper = mock(ReviewPlatformProjectionMapper.class);
        when(mapper.findReviewPage(null, null, null, 0L, 20))
                .thenReturn(List.of(row(UUID.randomUUID().toString(), "{", true, false)));

        assertThatThrownBy(() -> new MyBatisReviewPlatformProjectionStore(mapper, new ObjectMapper())
                .findReviewPage(null, 1, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload");
    }

    private ReviewPlatformProjectionMapper.PlatformReviewRow row(
            String reviewId, String payloadJson, boolean includeEvent, boolean includeReport) {
        LocalDateTime timestamp = LocalDateTime.of(2026, 8, 1, 8, 0);
        return new ReviewPlatformProjectionMapper.PlatformReviewRow(
                reviewId,
                includeEvent ? "INITIAL_REVIEW" : "PENDING",
                1,
                3L,
                timestamp,
                includeEvent ? UUID.randomUUID().toString() : null,
                includeEvent ? 1 : null,
                includeEvent ? 8L : null,
                includeEvent ? ReviewEventType.PLAN_CREATED.name() : null,
                includeEvent ? ReviewEventType.PLAN_CREATED.category().name() : null,
                includeEvent ? "INITIAL_REVIEW" : null,
                null,
                null,
                null,
                null,
                null,
                null,
                includeEvent ? 40 : null,
                includeEvent ? 1 : null,
                payloadJson,
                includeEvent ? timestamp : null,
                includeReport ? UUID.randomUUID().toString() : null,
                includeReport ? 2L : null,
                includeReport ? 1L : null,
                includeReport ? "a".repeat(64) : null,
                includeReport ? timestamp.plusMinutes(1) : null);
    }
}
