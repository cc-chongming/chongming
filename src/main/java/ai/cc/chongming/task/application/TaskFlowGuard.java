package ai.cc.chongming.task.application;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Server-side barrier for the manual requirement lifecycle endpoints: a requirement that still
 * owns a non-DONE development task must finish or be handled through the task flow before the
 * manual start-development/complete/cancel commands may proceed. Task dispatch itself goes
 * through {@link DevTaskCommandService} and is intentionally not guarded here.
 *
 * @author wangli
 */
@Component
public class TaskFlowGuard {

    private final DevTaskRepository devTaskRepository;

    public TaskFlowGuard(DevTaskRepository devTaskRepository) {
        this.devTaskRepository = Objects.requireNonNull(devTaskRepository, "devTaskRepository must not be null");
    }

    /**
     * Rejects the command when the requirement still has a linked development task that has not
     * reached DONE.
     *
     * @param requirementId requirement targeted by the manual lifecycle command
     * @throws RequirementDomainException with {@code REQUIREMENT_HAS_ACTIVE_TASK} when blocked
     */
    public void requireNoActiveTask(RequirementId requirementId) {
        RequirementId targetId = Objects.requireNonNull(requirementId, "requirementId must not be null");
        devTaskRepository.findByRequirementId(targetId)
                .filter(task -> task.status() != DevTaskStatus.DONE)
                .ifPresent(task -> {
                    throw new RequirementDomainException(
                            RequirementErrorCode.REQUIREMENT_HAS_ACTIVE_TASK,
                            "需求仍存在未完成的开发任务（状态 " + task.status() + "），请先在任务中心处理后再执行该操作");
                });
    }
}
