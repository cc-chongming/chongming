package ai.cc.chongming.review.infrastructure.review;

import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import ai.cc.chongming.review.domain.repository.ReviewPlatformProjectionStore;
import ai.cc.chongming.review.domain.repository.ReviewReportStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H2] Complete in-memory projection with exact filtering and pagination semantics.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryReviewPlatformProjectionStore implements ReviewPlatformProjectionStore {

    private final ReviewEventStore eventStore;
    private final ReviewReportStore reportStore;
    private final ReviewRegistry reviewRegistry;

    public InMemoryReviewPlatformProjectionStore(ReviewEventStore eventStore, ReviewReportStore reportStore) {
        this(eventStore, reportStore, ReviewRegistry.noop());
    }

    @Autowired
    public InMemoryReviewPlatformProjectionStore(
            ReviewEventStore eventStore, ReviewReportStore reportStore, ReviewRegistry reviewRegistry) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore must not be null");
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
    }

    @Override
    public PlatformReviewPage findReviewPage(ReviewProjectionFilter filter, int page, int size) {
        ReviewProjectionFilter effectiveFilter = filter == null ? new ReviewProjectionFilter(null, null) : filter;
        Map<ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId, ReviewReportStore.ReportMetadata> reportsByReview =
                reportStore.findLatestAcrossReviews(Integer.MAX_VALUE).stream()
                        .map(report -> new ReviewReportStore.ReportMetadata(
                                report.reviewId(), report.reportVersion(), report.gateVersion(), report.contentHash(), report.createdAt()))
                        .collect(Collectors.toMap(ReviewReportStore.ReportMetadata::reviewId, Function.identity(),
                                (left, right) -> left));
        Map<ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId, ai.cc.chongming.review.domain.event.ReviewEvent>
                latestEventsByReview = eventStore.findLatestAcrossReviews(Integer.MAX_VALUE).stream()
                .collect(Collectors.toMap(
                        ai.cc.chongming.review.domain.event.ReviewEvent::reviewId,
                        Function.identity(),
                        (left, right) -> left));
        List<PlatformReview> matched = reviewRegistry.findAll().stream()
                .filter(review -> effectiveFilter.stage() == null || review.stage() == effectiveFilter.stage())
                .filter(review -> !Boolean.TRUE.equals(effectiveFilter.activeOnly()) || !review.stage().isTerminal())
                .map(review -> new PlatformReview(
                        review.id(),
                        review.stage(),
                        review.attemptNo(),
                        review.version(),
                        latestEventsByReview.containsKey(review.id())
                                ? latestEventsByReview.get(review.id()).occurredAt()
                                : null,
                        latestEventsByReview.get(review.id()),
                        reportsByReview.get(review.id())))
                .filter(projection -> effectiveFilter.hasReport() == null
                        || effectiveFilter.hasReport() == (projection.latestReport() != null))
                .sorted(Comparator.comparing(
                                (PlatformReview projection) -> projection.updatedAt() == null
                                        ? Instant.EPOCH
                                        : projection.updatedAt())
                        .reversed()
                        .thenComparing(projection -> projection.reviewId().value(), Comparator.reverseOrder()))
                .toList();
        long requestedStart = ((long) page - 1L) * size;
        int start = requestedStart >= matched.size() ? matched.size() : (int) requestedStart;
        int end = Math.min(start + size, matched.size());
        return new PlatformReviewPage(matched.subList(start, end), matched.size());
    }
}
