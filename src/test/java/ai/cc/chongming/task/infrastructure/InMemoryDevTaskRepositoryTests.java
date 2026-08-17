package ai.cc.chongming.task.infrastructure;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.domain.protocol.DevTaskStateMachine;
import ai.cc.chongming.task.domain.repository.DevTaskRepository.TaskFilter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the in-memory development-task repository contract: optimistic version checks on
 * save, requirement-scoped uniqueness aligned with {@code uk_dev_task_requirement}, and the
 * keyword filter matching both the task title and the joined requirement title like the
 * MyBatis counterpart.
 *
 * @author wangli
 */
class InMemoryDevTaskRepositoryTests {

    private InMemoryDevTaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDevTaskRepository();
    }

    @Test
    void saveRejectsStaleVersionWritesWithVersionConflict() {
        DevTask stored = draft("版本校验任务");
        repository.save(stored);
        DevTask staleCopy = restore(stored, DevTaskStatus.DEVELOPING, 0L);

        assertThatThrownBy(() -> repository.save(staleCopy))
                .isInstanceOf(TaskDomainException.class)
                .satisfies(exception -> assertThat(((TaskDomainException) exception).errorCode())
                        .isEqualTo(TaskErrorCode.VERSION_CONFLICT));
        assertThat(repository.findById(stored.taskId()).orElseThrow().status())
                .isEqualTo(DevTaskStatus.PENDING_ASSIGN);
    }

    @Test
    void saveAcceptsTheNextVersionAfterATransition() {
        DevTask stored = draft("顺序写入任务");
        repository.save(stored);
        DevTask dispatched = repository.findById(stored.taskId()).orElseThrow();
        dispatched.assign("bob", "admin", new DevTaskStateMachine());

        repository.save(dispatched);

        DevTask reloaded = repository.findById(stored.taskId()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(DevTaskStatus.DEVELOPING);
        assertThat(reloaded.version()).isEqualTo(1L);
    }

    @Test
    void saveRejectsSecondTaskForTheSameRequirement() {
        RequirementId requirementId = new RequirementId(UUID.randomUUID());
        repository.save(DevTask.draft(new DevTaskId(UUID.randomUUID()), requirementId, null, "第一个任务"));

        assertThatThrownBy(() -> repository.save(
                DevTask.draft(new DevTaskId(UUID.randomUUID()), requirementId, null, "第二个任务")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(requirementId.value().toString());
    }

    @Test
    void keywordMatchesBothTaskTitleAndRequirementTitle() {
        DevTask task = draft("派发实现");
        task.withRequirementTitle("统一身份同步");
        repository.save(task);
        repository.save(draft("另一任务"));

        assertThat(repository.findPage(new TaskFilter(null, null, "派发", null), 1, 100).items())
                .singleElement()
                .satisfies(match -> assertThat(match.taskId()).isEqualTo(task.taskId()));
        assertThat(repository.findPage(new TaskFilter(null, null, "身份同步", null), 1, 100).items())
                .singleElement()
                .satisfies(match -> assertThat(match.taskId()).isEqualTo(task.taskId()));
        assertThat(repository.findPage(new TaskFilter(null, null, "不存在的关键字", null), 1, 100).items())
                .isEmpty();
    }

    @Test
    void pageReadFiltersByRequirementId() {
        DevTask first = draft("过滤任务甲");
        DevTask second = draft("过滤任务乙");
        repository.save(first);
        repository.save(second);

        assertThat(repository.findPage(new TaskFilter(null, null, null, first.requirementId()), 1, 100).items())
                .singleElement()
                .satisfies(match -> assertThat(match.taskId()).isEqualTo(first.taskId()));
    }

    @Test
    void mutationsOnLoadedCopiesStayIsolatedFromTheStoredAggregate() {
        DevTask stored = draft("副本隔离任务");
        repository.save(stored);
        DevTask loaded = repository.findById(stored.taskId()).orElseThrow();
        loaded.assign("bob", "admin", new DevTaskStateMachine());

        assertThat(repository.findById(stored.taskId()).orElseThrow().status())
                .isEqualTo(DevTaskStatus.PENDING_ASSIGN);
        assertThat(repository.findById(stored.taskId()).orElseThrow().version()).isZero();

        repository.save(loaded);

        assertThat(repository.findById(stored.taskId()).orElseThrow().status())
                .isEqualTo(DevTaskStatus.DEVELOPING);
        assertThat(repository.findById(stored.taskId()).orElseThrow().version()).isEqualTo(1L);
    }

    /**
     * [AIREVIEW-PLAN-027] The visibility feed returns exactly the requirement identifiers
     * whose dev task belongs to the assignee; blank or unknown names yield an empty set.
     */
    @Test
    void findRequirementIdsByAssigneeReturnsOnlyTheAssigneesRequirements() {
        DevTask first = draft("可见性任务甲");
        DevTask second = draft("可见性任务乙");
        DevTask otherOwner = draft("他人任务");
        repository.save(first);
        repository.save(second);
        repository.save(otherOwner);
        DevTask firstAssigned = repository.findById(first.taskId()).orElseThrow();
        firstAssigned.assign("dev-zhang", "admin", new DevTaskStateMachine());
        repository.save(firstAssigned);
        DevTask secondAssigned = repository.findById(second.taskId()).orElseThrow();
        secondAssigned.assign("dev-zhang", "admin", new DevTaskStateMachine());
        repository.save(secondAssigned);
        DevTask otherAssigned = repository.findById(otherOwner.taskId()).orElseThrow();
        otherAssigned.assign("dev-li", "admin", new DevTaskStateMachine());
        repository.save(otherAssigned);

        assertThat(repository.findRequirementIdsByAssignee("dev-zhang"))
                .containsExactlyInAnyOrder(first.requirementId(), second.requirementId());
        assertThat(repository.findRequirementIdsByAssignee("dev-li"))
                .containsExactly(otherOwner.requirementId());
        assertThat(repository.findRequirementIdsByAssignee("ghost")).isEmpty();
        assertThat(repository.findRequirementIdsByAssignee(null)).isEmpty();
        assertThat(repository.findRequirementIdsByAssignee("   ")).isEmpty();
    }

    private DevTask draft(String title) {
        return DevTask.draft(new DevTaskId(UUID.randomUUID()), new RequirementId(UUID.randomUUID()), null, title);
    }

    private DevTask restore(DevTask source, DevTaskStatus status, long version) {
        return DevTask.restore(
                source.taskId(),
                source.requirementId(),
                null,
                source.title(),
                status,
                "bob",
                "admin",
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                version);
    }
}
