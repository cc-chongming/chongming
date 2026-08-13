package ai.cc.chongming.task.application;

import ai.cc.chongming.auth.domain.User;
import ai.cc.chongming.auth.infrastructure.InMemoryUserRepository;
import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementRepository;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.infrastructure.InMemoryDevTaskRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises dispatch and acceptance commands against the in-memory stores, verifying the
 * linked requirement lifecycle advances in lockstep and that version/permission guards hold.
 *
 * @author wangli
 */
class DevTaskCommandServiceTests {

    private InMemoryDevTaskRepository devTaskRepository;
    private InMemoryRequirementRepository requirementRepository;
    private InMemoryUserRepository userRepository;
    private ReviewerIdentityProvider identityProvider;
    private RequirementCommandService requirementCommandService;
    private DevTaskCommandService commandService;

    @BeforeEach
    void setUp() {
        devTaskRepository = new InMemoryDevTaskRepository();
        requirementRepository = new InMemoryRequirementRepository();
        userRepository = new InMemoryUserRepository();
        identityProvider =
                () -> new ReviewerIdentityProvider.ReviewerIdentity("admin", Set.of());
        requirementCommandService = new RequirementCommandService(requirementRepository, identityProvider);
        commandService = new DevTaskCommandService(
                devTaskRepository, requirementRepository, userRepository, requirementCommandService);
        userRepository.save(User.newUser("bob", "PBKDF2$210000$c2FsdA==$aGFzaA==", "Bob", "USER"));
    }

    @Test
    void assignMovesTaskToDevelopingAndStartsLinkedRequirementDevelopment() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);

        DevTask assigned = commandService.assign(task.taskId(), "bob", "admin", task.version());

        assertThat(assigned.status()).isEqualTo(DevTaskStatus.DEVELOPING);
        assertThat(assigned.assigneeUsername()).isEqualTo("bob");
        assertThat(assigned.dispatcherUsername()).isEqualTo("admin");
        assertThat(requirementRepository.findById(requirement.id()).orElseThrow().status())
                .isEqualTo(RequirementStatus.DEVELOPING);
    }

    @Test
    void assignRejectsWhenLinkedRequirementIsNotApproved() {
        Requirement requirement = draftRequirement("未通过评审的需求");
        DevTask task = pendingTask(requirement);

        assertThatThrownBy(() -> commandService.assign(task.taskId(), "bob", "admin", task.version()))
                .isInstanceOf(TaskDomainException.class)
                .satisfies(exception -> assertThat(((TaskDomainException) exception).errorCode())
                        .isEqualTo(TaskErrorCode.TASK_REQUIREMENT_STATE_CONFLICT));
        assertThat(devTaskRepository.findById(task.taskId()).orElseThrow().status())
                .isEqualTo(DevTaskStatus.PENDING_ASSIGN);
    }

    @Test
    void assignRejectsUnknownAssigneeWithTaskNotFoundCode() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);

        assertThatThrownBy(() -> commandService.assign(task.taskId(), "ghost", "admin", task.version()))
                .isInstanceOf(TaskDomainException.class)
                .satisfies(exception -> assertThat(((TaskDomainException) exception).errorCode())
                        .isEqualTo(TaskErrorCode.TASK_NOT_FOUND));
    }

    @Test
    void assignWithStaleVersionSurfacesVersionConflict() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);

        assertThatThrownBy(() -> commandService.assign(task.taskId(), "bob", "admin", task.version() + 5L))
                .isInstanceOf(TaskDomainException.class)
                .satisfies(exception -> assertThat(((TaskDomainException) exception).errorCode())
                        .isEqualTo(TaskErrorCode.VERSION_CONFLICT));
    }

    @Test
    void assignLeavesTaskUntouchedWhenRequirementTransitionHitsVersionConflict() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);
        DevTaskCommandService failingCommandService =
                new DevTaskCommandService(devTaskRepository, requirementRepository, userRepository,
                        new RequirementCommandService(requirementRepository, identityProvider) {
                            @Override
                            public Requirement startDevelopment(RequirementId requirementId, long expectedVersion) {
                                throw new RequirementDomainException(
                                        RequirementErrorCode.VERSION_CONFLICT, "requirement version stale");
                            }
                        });

        assertThatThrownBy(() -> failingCommandService.assign(task.taskId(), "bob", "admin", task.version()))
                .isInstanceOf(RequirementDomainException.class)
                .satisfies(exception -> assertThat(((RequirementDomainException) exception).errorCode())
                        .isEqualTo(RequirementErrorCode.VERSION_CONFLICT));

        DevTask persisted = devTaskRepository.findById(task.taskId()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(DevTaskStatus.PENDING_ASSIGN);
        assertThat(persisted.version()).isZero();
    }

    @Test
    void submitAcceptanceByAssignedOwnerMovesTaskToPendingAcceptance() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);
        DevTask assigned = commandService.assign(task.taskId(), "bob", "admin", task.version());

        DevTask submitted = commandService.submitAcceptance(task.taskId(), "bob", assigned.version());

        assertThat(submitted.status()).isEqualTo(DevTaskStatus.PENDING_ACCEPTANCE);
        assertThat(requirementRepository.findById(requirement.id()).orElseThrow().status())
                .isEqualTo(RequirementStatus.DEVELOPING);
    }

    @Test
    void submitAcceptanceByAnyoneElseIsForbidden() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);
        DevTask assigned = commandService.assign(task.taskId(), "bob", "admin", task.version());

        assertThatThrownBy(() -> commandService.submitAcceptance(task.taskId(), "mallory", assigned.version()))
                .isInstanceOf(TaskDomainException.class)
                .satisfies(exception -> assertThat(((TaskDomainException) exception).errorCode())
                        .isEqualTo(TaskErrorCode.FORBIDDEN));
    }

    @Test
    void acceptCompletesTaskAndClosesLinkedRequirement() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);
        DevTask assigned = commandService.assign(task.taskId(), "bob", "admin", task.version());
        DevTask submitted = commandService.submitAcceptance(task.taskId(), "bob", assigned.version());

        DevTask accepted = commandService.accept(task.taskId(), "验收通过", submitted.version());

        assertThat(accepted.status()).isEqualTo(DevTaskStatus.DONE);
        assertThat(accepted.acceptanceNote()).isEqualTo("验收通过");
        assertThat(requirementRepository.findById(requirement.id()).orElseThrow().status())
                .isEqualTo(RequirementStatus.DONE);
    }

    @Test
    void rejectReturnsTaskToDevelopingAndKeepsRequirementDeveloping() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);
        DevTask assigned = commandService.assign(task.taskId(), "bob", "admin", task.version());
        DevTask submitted = commandService.submitAcceptance(task.taskId(), "bob", assigned.version());

        DevTask rejected = commandService.reject(task.taskId(), "缺少证据材料", submitted.version());

        assertThat(rejected.status()).isEqualTo(DevTaskStatus.DEVELOPING);
        assertThat(rejected.acceptanceNote()).isEqualTo("缺少证据材料");
        assertThat(requirementRepository.findById(requirement.id()).orElseThrow().status())
                .isEqualTo(RequirementStatus.DEVELOPING);
    }

    @Test
    void acceptLeavesTaskUntouchedWhenRequirementCompletionHitsVersionConflict() {
        Requirement requirement = approvedRequirement("统一身份同步");
        DevTask task = pendingTask(requirement);
        DevTask assigned = commandService.assign(task.taskId(), "bob", "admin", task.version());
        DevTask submitted = commandService.submitAcceptance(task.taskId(), "bob", assigned.version());
        DevTaskCommandService failingCommandService =
                new DevTaskCommandService(devTaskRepository, requirementRepository, userRepository,
                        new RequirementCommandService(requirementRepository, identityProvider) {
                            @Override
                            public Requirement complete(RequirementId requirementId, long expectedVersion) {
                                throw new RequirementDomainException(
                                        RequirementErrorCode.VERSION_CONFLICT, "requirement version stale");
                            }
                        });

        assertThatThrownBy(() -> failingCommandService.accept(task.taskId(), "验收通过", submitted.version()))
                .isInstanceOf(RequirementDomainException.class);

        DevTask persisted = devTaskRepository.findById(task.taskId()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(DevTaskStatus.PENDING_ACCEPTANCE);
        assertThat(persisted.version()).isEqualTo(submitted.version());
        assertThat(requirementRepository.findById(requirement.id()).orElseThrow().status())
                .isEqualTo(RequirementStatus.DEVELOPING);
    }

    @Test
    void commandsAgainstUnknownTaskSurfaceTaskNotFound() {
        assertThatThrownBy(() -> commandService.submitAcceptance(new DevTaskId(UUID.randomUUID()), "bob", 0L))
                .isInstanceOf(TaskDomainException.class)
                .satisfies(exception -> assertThat(((TaskDomainException) exception).errorCode())
                        .isEqualTo(TaskErrorCode.TASK_NOT_FOUND));
    }

    private Requirement approvedRequirement(String title) {
        Requirement requirement = draftRequirement(title);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        requirementCommandService.submitForReview(requirement.id(), reviewId, requirement.version());
        requirementCommandService.markReviewStarted(reviewId);
        requirementCommandService.applyGateDecision(reviewId, GateResult.PASS);
        return requirementRepository.findById(requirement.id()).orElseThrow();
    }

    private Requirement draftRequirement(String title) {
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), title, "任务流转验证", "admin", null, "cx-ai", "P1");
        requirementRepository.save(requirement);
        return requirement;
    }

    private DevTask pendingTask(Requirement requirement) {
        DevTask task = DevTask.draft(
                new DevTaskId(UUID.randomUUID()), requirement.id(), requirement.reviewId(), requirement.title());
        devTaskRepository.save(task);
        return task;
    }
}
