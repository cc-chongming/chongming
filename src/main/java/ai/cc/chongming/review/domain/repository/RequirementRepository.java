package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [AIREVIEW-PLAN-021#2] Storage boundary for requirements and their cross-review association.
 *
 * @author zyj
 */
public interface RequirementRepository {

    void save(Requirement requirement);

    /**
     * Deletes one requirement only when its persisted version still matches.
     *
     * @param requirementId requirement identifier
     * @param expectedVersion optimistic-lock version
     * @return whether exactly one matching requirement was deleted
     */
    boolean delete(RequirementId requirementId, long expectedVersion);

    Optional<Requirement> findById(RequirementId requirementId);

    Optional<Requirement> findByReviewId(ReviewId reviewId);

    RequirementPage findPage(RequirementFilter filter, int page, int size);

    Map<RequirementStatus, Long> countByStatus();

    /**
     * @author zyj
     */
    record RequirementFilter(RequirementStatus status, String assigneeId, String keyword) {
    }

    /**
     * @author zyj
     */
    record RequirementPage(List<Requirement> items, int page, int size, long total) {
        public RequirementPage {
            items = List.copyOf(items);
        }
    }
}
