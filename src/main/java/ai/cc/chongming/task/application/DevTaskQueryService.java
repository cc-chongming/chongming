package ai.cc.chongming.task.application;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import ai.cc.chongming.task.domain.repository.DevTaskRepository.TaskFilter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds public development-task read models. Page reads rely on the repository's joined
 * requirement title so no per-row requirement lookup is issued for the persistent profile.
 *
 * @author wangli
 */
@Service
public class DevTaskQueryService {

    private final DevTaskRepository devTaskRepository;
    private final RequirementRepository requirementRepository;

    public DevTaskQueryService(DevTaskRepository devTaskRepository, RequirementRepository requirementRepository) {
        this.devTaskRepository = Objects.requireNonNull(devTaskRepository, "devTaskRepository must not be null");
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
    }

    @Transactional(readOnly = true)
    public TaskPage findPage(String status, String assignee, String keyword, String requirementId, int page, int size) {
        DevTaskRepository.TaskPage result = devTaskRepository.findPage(
                new TaskFilter(
                        parseStatus(status),
                        normalize(assignee),
                        normalize(keyword),
                        parseRequirementId(requirementId)),
                page,
                size);
        List<DevTaskView> items = result.items().stream()
                .map(this::toView)
                .toList();
        return new TaskPage(items, result.page(), result.size(), result.total());
    }

    @Transactional(readOnly = true)
    public DevTaskView findById(DevTaskId taskId) {
        return devTaskRepository.findById(Objects.requireNonNull(taskId, "taskId must not be null"))
                .map(this::toView)
                .orElseThrow(() -> new TaskDomainException(
                        TaskErrorCode.TASK_NOT_FOUND, "dev task was not found"));
    }

    private DevTaskView toView(DevTask task) {
        String requirementTitle = task.requirementTitle();
        if (requirementTitle == null) {
            requirementTitle = requirementRepository.findById(task.requirementId())
                    .map(requirement -> requirement.title())
                    .orElse(null);
        }
        return new DevTaskView(
                task.taskId().value(),
                task.requirementId().value(),
                requirementTitle,
                task.reviewId() == null ? null : task.reviewId().value(),
                task.title(),
                task.status().name(),
                task.assigneeUsername(),
                task.assigneeDisplayName(),
                task.dispatcherUsername(),
                task.acceptanceNote(),
                task.currentHolderUsername(),
                task.handoffHistory(),
                task.version(),
                task.createdAt().toString(),
                task.updatedAt().toString());
    }

    private RequirementId parseRequirementId(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        try {
            return new RequirementId(UUID.fromString(normalized));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported requirementId: " + value, exception);
        }
    }

    private DevTaskStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DevTaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported dev task status: " + value, exception);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @author wangli
     */
    public record TaskPage(List<DevTaskView> items, int page, int size, long total) {
        public TaskPage {
            items = List.copyOf(items);
        }
    }

    /**
     * @author wangli
     */
    public record DevTaskView(
            UUID taskId,
            UUID requirementId,
            String requirementTitle,
            UUID reviewId,
            String title,
            String status,
            String assigneeUsername,
            String assigneeDisplayName,
            String dispatcherUsername,
            String acceptanceNote,
            String currentHolderUsername,
            List<ai.cc.chongming.task.domain.DevTaskTypes.HandoffEntry> handoffHistory,
            long version,
            String createdAt,
            String updatedAt) {
    }
}
