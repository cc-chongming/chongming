package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewPlatformProjectionStore;
import ai.cc.chongming.review.domain.repository.ReviewReportStore;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H2] Paged platform listing projected from the latest immutable fact of each review.
 *
 * @author zyj
 */
@Service
public class ReviewListQueryService {

    private final ReviewPlatformProjectionStore projectionStore;
    private final ReviewReportStore reportStore;

    public ReviewListQueryService(ReviewPlatformProjectionStore projectionStore, ReviewReportStore reportStore) {
        this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore must not be null");
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore must not be null");
    }

    @Transactional(readOnly = true)
    public ReviewPage findPage(String stage, Boolean hasReport, int page, int size) {
        validatePage(page, size);
        ReviewStage expectedStage = parseStage(stage);
        ReviewPlatformProjectionStore.PlatformReviewPage projection = projectionStore.findReviewPage(
                new ReviewPlatformProjectionStore.ReviewProjectionFilter(expectedStage, hasReport), page, size);
        List<ReviewView> items = projection.items().stream()
                .map(ReviewView::from)
                .toList();
        return new ReviewPage(items, page, size, projection.total());
    }

    @Transactional(readOnly = true)
    public ReportPage findReports(int page, int size) {
        validatePage(page, size);
        ReviewReportStore.ReportMetadataPage projection = reportStore.findLatestMetadataPage(page, size);
        return new ReportPage(projection.items().stream().map(ReportView::from).toList(),
                projection.page(), projection.size(), projection.total());
    }

    private ReviewStage parseStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        try {
            return ReviewStage.valueOf(stage.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported review stage: " + stage, exception);
        }
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be positive and size must be between 1 and 100");
        }
    }

    /**
     * @author zyj
     */
    public record ReviewPage(List<ReviewView> items, int page, int size, long total) {
        public ReviewPage {
            items = List.copyOf(items);
        }
    }

    /**
     * @author zyj
     */
    public record ReviewView(
            UUID reviewId,
            String stage,
            Integer progress,
            int attempt,
            long lastSequence,
            String lastEventType,
            String occurredAt,
            boolean hasReport,
            Long reportVersion) {
        static ReviewView from(ReviewPlatformProjectionStore.PlatformReview projection) {
            var event = projection.latestEvent();
            ReviewReportStore.ReportMetadata report = projection.latestReport();
            return new ReviewView(
                    projection.reviewId().value(),
                    projection.stage().name(),
                    event == null ? 0 : event.progress(),
                    projection.attemptNo(),
                    event == null ? 0L : event.sequence(),
                    event == null ? null : event.type().name(),
                    projection.updatedAt() == null ? null : projection.updatedAt().toString(),
                    report != null,
                    report == null ? null : report.reportVersion());
        }
    }

    /**
     * @author zyj
     */
    public record ReportView(UUID reviewId, long reportVersion, long gateVersion, String contentHash, String createdAt) {
        static ReportView from(ReviewReportStore.ReportMetadata report) {
            return new ReportView(
                    report.reviewId().value(),
                    report.reportVersion(),
                    report.gateVersion(),
                    report.contentHash(),
                    report.createdAt().toString());
        }
    }

    /**
     * @author zyj
     */
    public record ReportPage(List<ReportView> items, int page, int size, long total) {
        public ReportPage {
            items = List.copyOf(items);
        }
    }
}
