package ai.cc.chongming.task.infrastructure;

import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Process-local development-task repository for default and demo profiles, mirroring the
 * requirement repository's in-memory fallback. The requirement-scoped uniqueness that MySQL
 * enforces through {@code uk_dev_task_requirement} is replicated here so duplicate provisioning
 * fails the same way in both modes. {@link #save(DevTask)} is guarded by the instance monitor so
 * the uniqueness check plus the write form one atomic step: concurrent event-driven provisioning
 * and reconciliation can never insert two tasks for the same requirement. Writes additionally
 * apply the aggregate's optimistic-lock version exactly like the MyBatis update statement.
 * Stored rows are defensive copies so callers mutating their loaded aggregate never bypass the
 * version check on the next save.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryDevTaskRepository implements DevTaskRepository {

    private final Map<DevTaskId, DevTask> tasks = new ConcurrentHashMap<>();
    private final UserRepository userRepository;

    public InMemoryDevTaskRepository() {
        this(null);
    }

    @Autowired(required = false)
    public InMemoryDevTaskRepository(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public synchronized void save(DevTask task) {
        DevTask nonNullTask = Objects.requireNonNull(task, "task must not be null");
        DevTask existing = tasks.get(nonNullTask.taskId());
        requireVersion(nonNullTask, existing);
        requireUniqueRequirement(nonNullTask);
        tasks.put(nonNullTask.taskId(), copy(nonNullTask));
    }

    @Override
    public Optional<DevTask> findById(DevTaskId taskId) {
        return Optional.ofNullable(tasks.get(Objects.requireNonNull(taskId, "taskId must not be null")))
                .map(this::readCopy);
    }

    @Override
    public Optional<DevTask> findByRequirementId(RequirementId requirementId) {
        RequirementId targetId = Objects.requireNonNull(requirementId, "requirementId must not be null");
        return tasks.values().stream()
                .filter(task -> targetId.equals(task.requirementId()))
                .findFirst()
                .map(this::readCopy);
    }

    @Override
    public TaskPage findPage(TaskFilter filter, int page, int size) {
        validatePage(page, size);
        TaskFilter effectiveFilter = filter == null ? new TaskFilter(null, null, null, null) : filter;
        List<DevTask> matched = tasks.values().stream()
                .filter(task -> matches(task, effectiveFilter))
                .sorted(Comparator.comparing(DevTask::updatedAt).reversed()
                        .thenComparing(task -> task.taskId().value()))
                .map(this::readCopy)
                .toList();
        long requestedStart = ((long) page - 1L) * size;
        int start = requestedStart >= matched.size() ? matched.size() : (int) requestedStart;
        int end = Math.min(start + size, matched.size());
        return new TaskPage(matched.subList(start, end), page, size, matched.size());
    }

    @Override
    public Map<DevTaskStatus, Long> countByStatus() {
        Map<DevTaskStatus, Long> counts = new EnumMap<>(DevTaskStatus.class);
        tasks.values().forEach(task -> counts.merge(task.status(), 1L, Long::sum));
        return Map.copyOf(counts);
    }

    private void requireVersion(DevTask task, DevTask existing) {
        if (existing == null) {
            if (task.version() != 0L) {
                throw new TaskDomainException(
                        TaskErrorCode.VERSION_CONFLICT,
                        "new dev task must be a draft with version 0");
            }
            return;
        }
        if (existing.version() + 1L != task.version()) {
            throw new TaskDomainException(
                    TaskErrorCode.VERSION_CONFLICT,
                    "dev task version no longer matches the persisted aggregate");
        }
    }

    private void requireUniqueRequirement(DevTask task) {
        tasks.values().stream()
                .filter(stored -> stored.requirementId().equals(task.requirementId()))
                .filter(stored -> !stored.taskId().equals(task.taskId()))
                .findFirst()
                .ifPresent(conflicting -> {
                    throw new IllegalStateException(
                            "dev task already exists for requirement " + task.requirementId().value());
                });
    }

    private boolean matches(DevTask task, TaskFilter filter) {
        if (filter.status() != null && task.status() != filter.status()) {
            return false;
        }
        if (filter.assigneeUsername() != null && !filter.assigneeUsername().isBlank()
                && !filter.assigneeUsername().trim().equals(task.assigneeUsername())) {
            return false;
        }
        if (filter.requirementId() != null && !filter.requirementId().equals(task.requirementId())) {
            return false;
        }
        if (filter.keyword() == null || filter.keyword().isBlank()) {
            return true;
        }
        String keyword = filter.keyword().trim().toLowerCase(Locale.ROOT);
        if (task.title().toLowerCase(Locale.ROOT).contains(keyword)) {
            return true;
        }
        String requirementTitle = task.requirementTitle();
        return requirementTitle != null && requirementTitle.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private DevTask readCopy(DevTask stored) {
        return enrichAssigneeDisplayName(copy(stored));
    }

    private DevTask enrichAssigneeDisplayName(DevTask task) {
        if (userRepository != null && task.assigneeUsername() != null && task.assigneeDisplayName() == null) {
            userRepository.findByUsername(task.assigneeUsername())
                    .ifPresent(user -> task.withAssigneeDisplayName(user.displayName()));
        }
        return task;
    }

    private DevTask copy(DevTask task) {
        DevTask copy = DevTask.restore(
                task.taskId(),
                task.requirementId(),
                task.reviewId(),
                task.title(),
                task.status(),
                task.assigneeUsername(),
                task.dispatcherUsername(),
                task.acceptanceNote(),
                task.createdAt(),
                task.updatedAt(),
                task.version());
        copy.withRequirementTitle(task.requirementTitle());
        copy.withAssigneeDisplayName(task.assigneeDisplayName());
        return copy;
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be positive and size must be between 1 and 100");
        }
    }
}
