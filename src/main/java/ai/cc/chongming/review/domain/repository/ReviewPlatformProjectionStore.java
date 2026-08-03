package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H2] Provides complete, paged platform projections rather than a bounded
 * event window filtered in the application layer.
 *
 * @author zyj
 */
public interface ReviewPlatformProjectionStore {

    PlatformReviewPage findReviewPage(ReviewProjectionFilter filter, int page, int size);

    /**
     * @author zyj
     */
    record ReviewProjectionFilter(ReviewStage stage, Boolean hasReport, Boolean activeOnly) {
        public ReviewProjectionFilter(ReviewStage stage, Boolean hasReport) {
            this(stage, hasReport, null);
        }
    }

    /**
     * @author zyj
     */
    record PlatformReview(
            ReviewId reviewId,
            ReviewStage stage,
            int attemptNo,
            long version,
            Instant updatedAt,
            ReviewEvent latestEvent,
            ReviewReportStore.ReportMetadata latestReport) {
        public PlatformReview {
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            Objects.requireNonNull(stage, "stage must not be null");
            if (attemptNo < 1 || version < 0) {
                throw new IllegalArgumentException("attemptNo and version must be non-negative state values");
            }
            if (latestEvent != null && !reviewId.equals(latestEvent.reviewId())) {
                throw new IllegalArgumentException("latestEvent must belong to the review root");
            }
        }
    }

    /**
     * @author zyj
     */
    record PlatformReviewPage(List<PlatformReview> items, long total) {
        public PlatformReviewPage {
            items = List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
        }
    }
}
