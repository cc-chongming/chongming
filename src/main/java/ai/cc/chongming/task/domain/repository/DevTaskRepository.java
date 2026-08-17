package ai.cc.chongming.task.domain.repository;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Storage boundary for development tasks. {@link #save(DevTask)} applies the aggregate's
 * optimistic-lock version; implementations reject stale writes.
 *
 * @author wangli
 */
public interface DevTaskRepository {

    void save(DevTask task);

    Optional<DevTask> findById(DevTaskId taskId);

    Optional<DevTask> findByRequirementId(RequirementId requirementId);

    TaskPage findPage(TaskFilter filter, int page, int size);

    Map<DevTaskStatus, Long> countByStatus();

    /**
     * [AIREVIEW-PLAN-027] Requirement identifiers whose dev task is owned by the given assignee;
     * feeds the requirement visibility scope for non-administrator viewers.
     *
     * @param username assignee login name; blank or unknown names yield an empty set
     * @return requirement identifiers bound to the assignee's dev tasks
     */
    Set<RequirementId> findRequirementIdsByAssignee(String username);

    /**
     * @author wangli
     */
    record TaskFilter(DevTaskStatus status, String assigneeUsername, String keyword, RequirementId requirementId) {
    }

    /**
     * @author wangli
     */
    record TaskPage(List<DevTask> items, int page, int size, long total) {
        public TaskPage {
            items = List.copyOf(items);
        }
    }
}
