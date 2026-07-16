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
}
