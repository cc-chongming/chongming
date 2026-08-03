package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.util.List;
import java.util.Optional;

/**
 * [AIREVIEW-PLAN-011#1.4] Append-only persistence boundary for public review report versions.
 *
 * @author wangli
 */
public interface ReviewReportStore {

    void append(ReviewReport report);

    Optional<ReviewReport> findLatest(ReviewId reviewId);

    Optional<ReviewReport> findVersion(ReviewId reviewId, long reportVersion);

    List<ReviewReport> findVersions(ReviewId reviewId);

    /**
     * Returns the latest report for each review, newest first, for platform report projections.
     * The caller supplies an explicit bounded page/window size; this method must not silently reduce it.
     */
    List<ReviewReport> findLatestAcrossReviews(int limit);

    /**
     * Returns a bounded metadata-only page for platform lists. Report bodies remain available only from review-scoped reads.
     */
    ReportMetadataPage findLatestMetadataPage(int page, int size);

    /**
     * @author zyj
     */
    record ReportMetadata(ReviewId reviewId, long reportVersion, long gateVersion, String contentHash, java.time.Instant createdAt) {
    }

    /**
     * @author zyj
     */
    record ReportMetadataPage(List<ReportMetadata> items, int page, int size, long total) {
        public ReportMetadataPage {
            items = List.copyOf(items);
            if (page < 1 || size < 1 || total < 0) {
                throw new IllegalArgumentException("metadata page values are invalid");
            }
        }
    }
}
