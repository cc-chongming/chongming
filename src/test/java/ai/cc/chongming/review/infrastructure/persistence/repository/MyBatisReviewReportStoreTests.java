package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewReportMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-M1] Checks durable-store conversion independently of the process-local store.
 *
 * @author zyj
 */
class MyBatisReviewReportStoreTests {

    @Test
    void reloadsLatestReportFromTheMapperAfterCreatingANewStoreInstance() {
        InMemoryMapper mapper = new InMemoryMapper();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        mapper.reviewId = reviewId.value().toString();
        MyBatisReviewReportStore firstStore = new MyBatisReviewReportStore(mapper);
        ReviewReport expected = new ReviewReport(
                UUID.randomUUID(), reviewId, 1L, 2L, "a".repeat(64), "{\"status\":\"PASS\"}",
                "# 报告", Instant.parse("2026-08-01T00:00:00Z"));

        firstStore.append(expected);
        MyBatisReviewReportStore restartedStore = new MyBatisReviewReportStore(mapper);

        assertThat(restartedStore.findLatest(reviewId)).contains(expected);
        assertThat(restartedStore.findLatestAcrossReviews(20)).containsExactly(expected);
    }

    @Test
    void returnsMetadataOnlyPageAndAppliesDefaultGateVersion() {
        InMemoryMapper mapper = new InMemoryMapper();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        mapper.rows.add(new ReviewReportMapper.ReportRow(
                UUID.randomUUID().toString(), reviewId.value().toString(), 1, 2L, null, "b".repeat(64),
                "{\"status\":\"PASS\"}", "# 评审", LocalDateTime.of(2026, 8, 1, 8, 0)));
        MyBatisReviewReportStore store = new MyBatisReviewReportStore(mapper);

        var page = store.findLatestMetadataPage(1, 10);

        assertThat(page).extracting(
                ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadataPage::total,
                value -> value.items().size()).containsExactly(1L, 1);
        assertThat(page.items().getFirst()).extracting(
                ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::reviewId,
                ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::reportVersion,
                ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::gateVersion)
                .containsExactly(reviewId, 2L, 1L);
    }

    @Test
    void pagesOnlyTheLatestReportForEachReviewWithTheSqlTieBreakOrder() {
        InMemoryMapper mapper = new InMemoryMapper();
        ReviewId lowerReviewId = new ReviewId(new UUID(0L, 1L));
        ReviewId higherReviewId = new ReviewId(new UUID(0L, 2L));
        LocalDateTime timestamp = LocalDateTime.of(2026, 8, 1, 8, 0);
        mapper.rows.add(reportRow(lowerReviewId, 1L, timestamp));
        mapper.rows.add(reportRow(lowerReviewId, 2L, timestamp));
        mapper.rows.add(reportRow(higherReviewId, 1L, timestamp));
        MyBatisReviewReportStore store = new MyBatisReviewReportStore(mapper);

        assertThat(store.findLatestAcrossReviews(10)).extracting(ReviewReport::reviewId, ReviewReport::reportVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(higherReviewId, 1L),
                        org.assertj.core.groups.Tuple.tuple(lowerReviewId, 2L));
        assertThat(store.findLatestMetadataPage(1, 1).items()).singleElement()
                .extracting(
                        ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::reviewId,
                        ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::reportVersion)
                .containsExactly(higherReviewId, 1L);
        var secondPage = store.findLatestMetadataPage(2, 1);
        assertThat(secondPage.total()).isEqualTo(2L);
        assertThat(secondPage.items()).singleElement().extracting(
                ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::reviewId,
                ai.cc.chongming.review.domain.repository.ReviewReportStore.ReportMetadata::reportVersion)
                .containsExactly(lowerReviewId, 2L);
    }

    @Test
    void rejectsInvalidQueriesAndFailedDurableWrites() {
        InMemoryMapper mapper = new InMemoryMapper();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        MyBatisReviewReportStore store = new MyBatisReviewReportStore(mapper);

        assertThatIllegalArgumentException().isThrownBy(() -> store.findLatestAcrossReviews(0));
        assertThatIllegalArgumentException().isThrownBy(() -> store.findLatestMetadataPage(0, 20));
        mapper.reviewId = null;
        assertThatThrownBy(() -> store.append(new ReviewReport(
                UUID.randomUUID(), reviewId, 1L, 1L, "a".repeat(64), "{}", "# 评审", Instant.EPOCH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exist");
    }

    private ReviewReportMapper.ReportRow reportRow(ReviewId reviewId, long version, LocalDateTime createdAt) {
        return new ReviewReportMapper.ReportRow(
                UUID.randomUUID().toString(), reviewId.value().toString(), 1, version, 1L, "c".repeat(64),
                "{\"status\":\"PASS\"}", "# 评审", createdAt);
    }

    private static final class InMemoryMapper implements ReviewReportMapper {

        private final List<ReportRow> rows = new ArrayList<>();
        private String reviewId;

        @Override
        public int insert(ReportRow row) {
            rows.add(row);
            return 1;
        }

        @Override
        public ReportRow findLatest(String reviewId) {
            return rows.stream().filter(row -> row.reviewId().equals(reviewId))
                    .max(Comparator.comparingLong(ReportRow::reportVersion)).orElse(null);
        }

        @Override
        public ReportRow findVersion(String reviewId, long reportVersion) {
            return rows.stream().filter(row -> row.reviewId().equals(reviewId) && row.reportVersion() == reportVersion)
                    .findFirst().orElse(null);
        }

        @Override
        public List<ReportRow> findVersions(String reviewId) {
            return rows.stream().filter(row -> row.reviewId().equals(reviewId))
                    .sorted(Comparator.comparingLong(ReportRow::reportVersion)).toList();
        }

        @Override
        public List<ReportRow> findLatestAcrossReviews(int limit) {
            return latestRows().stream()
                    .sorted(Comparator.comparing(ReportRow::createdAt).reversed()
                            .thenComparing(ReportRow::reviewId, Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ReportMetadataRow> findLatestMetadataPage(long offset, int limit) {
            return latestRows().stream()
                    .sorted(Comparator.comparing(ReportRow::createdAt).reversed()
                            .thenComparing(ReportRow::reviewId, Comparator.reverseOrder()))
                    .skip(offset)
                    .limit(limit)
                    .map(row -> new ReportMetadataRow(
                            row.reviewId(), row.reportVersion(), row.gateVersion(), row.contentHash(), row.createdAt()))
                    .toList();
        }

        @Override
        public long countLatestMetadata() {
            return latestRows().size();
        }

        @Override
        public Integer findCurrentAttempt(String candidateReviewId) {
            return reviewId != null && reviewId.equals(candidateReviewId) ? 1 : null;
        }

        private List<ReportRow> latestRows() {
            return rows.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ReportRow::reviewId,
                            row -> row,
                            java.util.function.BinaryOperator.maxBy(Comparator.comparingLong(ReportRow::reportVersion))))
                    .values().stream()
                    .toList();
        }
    }
}
