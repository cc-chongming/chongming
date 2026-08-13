package ai.cc.chongming.task.infrastructure;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.DevTaskMapper;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
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
 * Persistent development-task repository with optimistic version checks, mirroring the
 * requirement repository wiring.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisDevTaskRepository implements DevTaskRepository {

    private final DevTaskMapper mapper;

    public MyBatisDevTaskRepository(DevTaskMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public void save(DevTask task) {
        DevTaskMapper.DevTaskRow row = toRow(Objects.requireNonNull(task, "task must not be null"));
        int affectedRows = task.version() == 0L
                ? mapper.insert(row)
                : mapper.update(row, task.version() - 1L);
        if (affectedRows != 1) {
            throw new TaskDomainException(
                    TaskErrorCode.VERSION_CONFLICT,
                    "dev task version no longer matches the persisted aggregate");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DevTask> findById(DevTaskId taskId) {
        return Optional.ofNullable(
                        mapper.findById(Objects.requireNonNull(taskId, "taskId must not be null").value().toString()))
                .map(this::toTask);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DevTask> findByRequirementId(RequirementId requirementId) {
        return Optional.ofNullable(
                        mapper.findByRequirementId(
                                Objects.requireNonNull(requirementId, "requirementId must not be null").value().toString()))
                .map(this::toTask);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskPage findPage(TaskFilter filter, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be positive and size must be between 1 and 100");
        }
        TaskFilter effectiveFilter = filter == null ? new TaskFilter(null, null, null, null) : filter;
        String status = effectiveFilter.status() == null ? null : effectiveFilter.status().name();
        String assigneeUsername = normalize(effectiveFilter.assigneeUsername());
        String keyword = normalize(effectiveFilter.keyword());
        String requirementId = effectiveFilter.requirementId() == null
                ? null
                : effectiveFilter.requirementId().value().toString();
        long total = mapper.countPage(status, assigneeUsername, keyword, requirementId);
        long offset = ((long) page - 1L) * size;
        List<DevTask> items = mapper.findPage(status, assigneeUsername, keyword, requirementId, offset, size).stream()
                .map(this::toTask)
                .toList();
        return new TaskPage(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<DevTaskStatus, Long> countByStatus() {
        Map<DevTaskStatus, Long> counts = new EnumMap<>(DevTaskStatus.class);
        mapper.countByStatus().forEach(row -> counts.put(DevTaskStatus.valueOf(row.status()), row.total()));
        return Map.copyOf(counts);
    }

    private DevTaskMapper.DevTaskRow toRow(DevTask task) {
        return new DevTaskMapper.DevTaskRow(
                task.taskId().value().toString(),
                task.requirementId().value().toString(),
                task.reviewId() == null ? null : task.reviewId().value().toString(),
                task.title(),
                task.status().name(),
                task.assigneeUsername(),
                task.dispatcherUsername(),
                task.acceptanceNote(),
                task.version(),
                task.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime(),
                task.updatedAt().atOffset(ZoneOffset.UTC).toLocalDateTime(),
                null,
                null);
    }

    private DevTask toTask(DevTaskMapper.DevTaskRow row) {
        DevTask task = DevTask.restore(
                new DevTaskId(UUID.fromString(row.taskId())),
                new RequirementId(UUID.fromString(row.requirementId())),
                row.reviewId() == null ? null : new ReviewId(UUID.fromString(row.reviewId())),
                row.title(),
                DevTaskStatus.valueOf(row.status()),
                row.assigneeUsername(),
                row.dispatcherUsername(),
                row.acceptanceNote(),
                row.createdAt().toInstant(ZoneOffset.UTC),
                row.updatedAt().toInstant(ZoneOffset.UTC),
                row.version());
        task.withRequirementTitle(row.requirementTitle());
        task.withAssigneeDisplayName(row.assigneeDisplayName());
        return task;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
