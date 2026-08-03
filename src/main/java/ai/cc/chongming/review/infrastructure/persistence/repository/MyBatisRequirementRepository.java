package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.infrastructure.persistence.mapper.RequirementMapper;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#2] Persistent requirement repository with optimistic version checks.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisRequirementRepository implements RequirementRepository {

    private final RequirementMapper mapper;

    public MyBatisRequirementRepository(RequirementMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public void save(Requirement requirement) {
        RequirementMapper.RequirementRow row = toRow(Objects.requireNonNull(requirement, "requirement must not be null"));
        int affectedRows = requirement.version() == 0L
                ? mapper.insert(row)
                : mapper.update(row, requirement.version() - 1L);
        if (affectedRows != 1) {
            throw new RequirementDomainException(
                    RequirementErrorCode.VERSION_CONFLICT,
                    "requirement version no longer matches the persisted aggregate");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Requirement> findById(RequirementId requirementId) {
        return Optional.ofNullable(mapper.findById(requirementId.value().toString())).map(this::toRequirement);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Requirement> findByReviewId(ReviewId reviewId) {
        return Optional.ofNullable(mapper.findByReviewId(reviewId.value().toString())).map(this::toRequirement);
    }

    @Override
    @Transactional(readOnly = true)
    public RequirementPage findPage(RequirementFilter filter, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be positive and size must be between 1 and 100");
        }
        RequirementFilter effectiveFilter = filter == null ? new RequirementFilter(null, null, null) : filter;
        String status = effectiveFilter.status() == null ? null : effectiveFilter.status().name();
        String assigneeId = normalize(effectiveFilter.assigneeId());
        String keyword = normalize(effectiveFilter.keyword());
        long total = mapper.countPage(status, assigneeId, keyword);
        long offset = ((long) page - 1L) * size;
        List<Requirement> items = mapper.findPage(status, assigneeId, keyword, offset, size).stream()
                .map(this::toRequirement)
                .toList();
        return new RequirementPage(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<RequirementStatus, Long> countByStatus() {
        Map<RequirementStatus, Long> counts = new EnumMap<>(RequirementStatus.class);
        mapper.countByStatus().forEach(row -> counts.put(RequirementStatus.valueOf(row.status()), row.total()));
        return Map.copyOf(counts);
    }

    private RequirementMapper.RequirementRow toRow(Requirement requirement) {
        return new RequirementMapper.RequirementRow(
                requirement.id().value().toString(),
                requirement.title(),
                requirement.description(),
                requirement.status().name(),
                requirement.creatorId(),
                requirement.assigneeId(),
                requirement.repositoryPath(),
                requirement.priority(),
                requirement.reviewId() == null ? null : requirement.reviewId().value().toString(),
                requirement.version(),
                requirement.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime(),
                requirement.updatedAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    private Requirement toRequirement(RequirementMapper.RequirementRow row) {
        return Requirement.restore(
                new RequirementId(UUID.fromString(row.id())),
                row.title(),
                row.description(),
                row.creatorId(),
                row.assigneeId(),
                row.repositoryPath(),
                row.priority(),
                RequirementStatus.valueOf(row.status()),
                row.reviewId() == null ? null : new ReviewId(UUID.fromString(row.reviewId())),
                row.createdAt().toInstant(ZoneOffset.UTC),
                row.updatedAt().toInstant(ZoneOffset.UTC),
                row.version());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
