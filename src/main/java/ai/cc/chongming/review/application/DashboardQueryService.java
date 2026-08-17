package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import ai.cc.chongming.review.domain.repository.ReviewPlatformProjectionStore;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#4] Builds the platform dashboard from independent requirement and review projections.
 *
 * @author zyj
 */
@Service
public class DashboardQueryService {

    private static final int ACTIVE_REVIEW_LIMIT = 12;
    private static final int ACTIVITY_LIMIT = 20;

    private final RequirementRepository requirementRepository;
    private final ReviewEventStore eventStore;
    private final ReviewPlatformProjectionStore projectionStore;

    public DashboardQueryService(
            RequirementRepository requirementRepository,
            ReviewEventStore eventStore,
            ReviewPlatformProjectionStore projectionStore) {
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore must not be null");
    }

    @Transactional(readOnly = true)
    public DashboardView getDashboard() {
        return getDashboard(null);
    }

    /**
     * [AIREVIEW-PLAN-027] Viewer-scoped dashboard; requirement status counts converge to the
     * caller's visibility while review projections keep their platform-wide semantics. A
     * {@code null} visibility keeps the historical platform-wide dashboard.
     */
    @Transactional(readOnly = true)
    public DashboardView getDashboard(RequirementRepository.RequirementVisibility visibility) {
        Map<RequirementStatus, Long> persistedCounts = visibility == null
                ? requirementRepository.countByStatus()
                : requirementRepository.countByStatus(visibility);
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (RequirementStatus status : RequirementStatus.values()) {
            statusCounts.put(status.name(), persistedCounts.getOrDefault(status, 0L));
        }
        ReviewPlatformProjectionStore.PlatformReviewPage activeProjection = projectionStore.findReviewPage(
                new ReviewPlatformProjectionStore.ReviewProjectionFilter(null, null, true), 1, ACTIVE_REVIEW_LIMIT);
        List<ReviewView> activeReviews = activeProjection.items().stream()
                .map(ReviewView::from)
                .toList();
        List<ActivityView> activities = eventStore.findRecentAcrossReviews(ACTIVITY_LIMIT).stream()
                .map(ActivityView::from)
                .toList();
        long pendingRequirements = persistedCounts.getOrDefault(RequirementStatus.PENDING_REVIEW, 0L)
                + persistedCounts.getOrDefault(RequirementStatus.REVIEWING, 0L);
        return new DashboardView(
                Map.copyOf(statusCounts),
                pendingRequirements,
                activeProjection.total(),
                activeReviews,
                activities);
    }

    /**
     * @author zyj
     */
    public record DashboardView(
            Map<String, Long> requirementStatusCounts,
            long pendingRequirementCount,
            long activeReviewCount,
            List<ReviewView> activeReviews,
            List<ActivityView> recentActivities) {
        public DashboardView {
            requirementStatusCounts = Map.copyOf(requirementStatusCounts);
            activeReviews = List.copyOf(activeReviews);
            recentActivities = List.copyOf(recentActivities);
        }
    }

    /**
     * @author zyj
     */
    public record ReviewView(String reviewId, String stage, Integer progress, int attempt, String occurredAt) {
        static ReviewView from(ReviewPlatformProjectionStore.PlatformReview review) {
            return new ReviewView(
                    review.reviewId().value().toString(),
                    review.stage().name(),
                    review.latestEvent() == null || review.latestEvent().progress() == null
                            ? 0
                            : review.latestEvent().progress(),
                    review.attemptNo(),
                    review.updatedAt() == null ? null : review.updatedAt().toString());
        }
    }

    /**
     * @author zyj
     */
    public record ActivityView(
            String reviewId, long sequence, String type, String stage, String summary, String occurredAt) {
        static ActivityView from(ReviewEvent event) {
            return new ActivityView(
                    event.reviewId().value().toString(),
                    event.sequence(),
                    event.type().name(),
                    event.stage().name(),
                    event.payload().getOrDefault("publicSummary", event.payload().getOrDefault("summary", event.type().name())),
                    event.occurredAt().toString());
        }
    }
}
