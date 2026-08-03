package ai.cc.chongming.review.infrastructure.review;

import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * [AIREVIEW-PLAN-021#2] Process-local requirement repository for default and demo profiles.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRequirementRepository implements RequirementRepository {

    private final Map<RequirementId, Requirement> requirements = new ConcurrentHashMap<>();

    @Override
    public void save(Requirement requirement) {
        Requirement nonNullRequirement = Objects.requireNonNull(requirement, "requirement must not be null");
        requirements.put(nonNullRequirement.id(), nonNullRequirement);
    }

    @Override
    public Optional<Requirement> findById(RequirementId requirementId) {
        return Optional.ofNullable(requirements.get(Objects.requireNonNull(requirementId, "requirementId must not be null")));
    }

    @Override
    public Optional<Requirement> findByReviewId(ReviewId reviewId) {
        return requirements.values().stream()
                .filter(requirement -> Objects.equals(requirement.reviewId(), reviewId))
                .findFirst();
    }

    @Override
    public RequirementPage findPage(RequirementFilter filter, int page, int size) {
        validatePage(page, size);
        RequirementFilter effectiveFilter = filter == null ? new RequirementFilter(null, null, null) : filter;
        List<Requirement> matched = requirements.values().stream()
                .filter(requirement -> matches(requirement, effectiveFilter))
                .sorted(Comparator.comparing(Requirement::updatedAt).reversed()
                        .thenComparing(requirement -> requirement.id().value()))
                .toList();
        long requestedStart = ((long) page - 1L) * size;
        int start = requestedStart >= matched.size() ? matched.size() : (int) requestedStart;
        int end = Math.min(start + size, matched.size());
        return new RequirementPage(matched.subList(start, end), page, size, matched.size());
    }

    @Override
    public Map<RequirementStatus, Long> countByStatus() {
        Map<RequirementStatus, Long> counts = new EnumMap<>(RequirementStatus.class);
        requirements.values().forEach(requirement -> counts.merge(requirement.status(), 1L, Long::sum));
        return Map.copyOf(counts);
    }

    private boolean matches(Requirement requirement, RequirementFilter filter) {
        if (filter.status() != null && requirement.status() != filter.status()) {
            return false;
        }
        if (filter.assigneeId() != null && !filter.assigneeId().isBlank()
                && !filter.assigneeId().trim().equals(requirement.assigneeId())) {
            return false;
        }
        if (filter.keyword() == null || filter.keyword().isBlank()) {
            return true;
        }
        String keyword = filter.keyword().trim().toLowerCase(Locale.ROOT);
        return requirement.title().toLowerCase(Locale.ROOT).contains(keyword)
                || requirement.description().toLowerCase(Locale.ROOT).contains(keyword);
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be positive and size must be between 1 and 100");
        }
    }
}
