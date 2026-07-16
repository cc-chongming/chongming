package ai.cc.chongming.review.infrastructure.report;

import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewReportStore;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [AIREVIEW-PLAN-011#1.4] Process-local append-only report store until the MyBatis writer is enabled.
 *
 * @author wangli
 */
@Repository
public class InMemoryReviewReportStore implements ReviewReportStore {

    private final Map<ReviewId, List<ReviewReport>> reports = new ConcurrentHashMap<>();

    @Override
    public synchronized void append(ReviewReport report) {
        List<ReviewReport> versions = reports.computeIfAbsent(report.reviewId(), ignored -> new ArrayList<>());
        if (!versions.isEmpty() && versions.getLast().reportVersion() >= report.reportVersion()) {
            throw new IllegalStateException("review report version is stale");
        }
        versions.add(report);
    }

    @Override
    public Optional<ReviewReport> findLatest(ReviewId reviewId) {
        List<ReviewReport> versions = reports.get(reviewId);
        return versions == null || versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast());
    }

    @Override
    public Optional<ReviewReport> findVersion(ReviewId reviewId, long reportVersion) {
        if (reportVersion < 1) {
            return Optional.empty();
        }
        return reports.getOrDefault(reviewId, List.of()).stream()
                .filter(report -> report.reportVersion() == reportVersion)
                .findFirst();
    }

    @Override
    public List<ReviewReport> findVersions(ReviewId reviewId) {
        return List.copyOf(reports.getOrDefault(reviewId, List.of()));
    }
}
