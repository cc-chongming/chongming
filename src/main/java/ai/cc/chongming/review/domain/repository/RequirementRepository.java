package ai.cc.chongming.review.domain.repository;

import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
     * [AIREVIEW-PLAN-027] Status counts restricted to one viewer's visibility scope; a
     * {@code null} visibility keeps the historical platform-wide totals.
     *
     * @param visibility viewer scope or {@code null} for all requirements
     * @return per-status counts visible to the viewer
     */
    Map<RequirementStatus, Long> countByStatus(RequirementVisibility visibility);

    /**
     * [AIREVIEW-PLAN-027] Viewer-scoped visibility component: a requirement is visible when the
     * viewer created it or owns a dev task bound to it. {@code null} visibility means the full
     * platform-wide set (administrators and demo profiles without authentication).
     *
     * @author wangli
     */
    record RequirementVisibility(String viewerUsername, Set<RequirementId> assignedRequirementIds) {
        public RequirementVisibility {
            viewerUsername = Objects.requireNonNull(viewerUsername, "viewerUsername must not be null");
            assignedRequirementIds = assignedRequirementIds == null ? Set.of() : Set.copyOf(assignedRequirementIds);
        }
    }

    /**
     * @author zyj
     */
    record RequirementFilter(
            RequirementStatus status, String assigneeId, String keyword, RequirementVisibility visibility) {

        /**
         * [AIREVIEW-PLAN-027] Legacy constructor delegating to an unrestricted (platform-wide) read.
         */
        public RequirementFilter(RequirementStatus status, String assigneeId, String keyword) {
            this(status, assigneeId, keyword, null);
        }
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
