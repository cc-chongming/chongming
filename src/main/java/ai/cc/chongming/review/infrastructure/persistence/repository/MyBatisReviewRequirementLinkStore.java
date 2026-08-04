package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRequirementLinkStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#2][REQLIFE-H1] Reserves a pending review before a requirement transitions to pending review.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewRequirementLinkStore implements ReviewRequirementLinkStore {

    private final ReviewPersistenceMapper mapper;

    public MyBatisReviewRequirementLinkStore(ReviewPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public boolean tryBindPendingReview(ReviewId reviewId, RequirementId requirementId) {
        ReviewPersistenceMapper.ReviewRequirementBindingRow binding =
                mapper.lockReviewRequirementBinding(reviewId.value().toString());
        if (binding == null || !"PENDING".equals(binding.stage())) {
            return false;
        }
        if (binding.requirementId() != null) {
            return binding.requirementId().equals(requirementId.value().toString());
        }
        return mapper.linkPendingUnboundReviewToRequirement(
                requirementId.value().toString(), reviewId.value().toString()) == 1;
    }

    @Override
    @Transactional
    public void unlinkRequirement(RequirementId requirementId) {
        mapper.unlinkRequirement(Objects.requireNonNull(requirementId, "requirementId must not be null").value().toString());
    }
}
