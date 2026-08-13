package ai.cc.chongming.task.application;

import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.domain.protocol.DevTaskStateMachine;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies development-task commands through the task aggregate while keeping the linked
 * requirement lifecycle in lockstep: dispatch starts requirement development, acceptance
 * completes it, and rejection leaves it untouched.
 *
 * @author wangli
 */
@Service
public class DevTaskCommandService {

    private final DevTaskRepository devTaskRepository;
    private final RequirementRepository requirementRepository;
    private final UserRepository userRepository;
    private final RequirementCommandService requirementCommandService;
    private final DevTaskStateMachine stateMachine = new DevTaskStateMachine();

    public DevTaskCommandService(
            DevTaskRepository devTaskRepository,
            RequirementRepository requirementRepository,
            RequirementCommandService requirementCommandService) {
        this(devTaskRepository, requirementRepository, null, requirementCommandService);
    }

    @Autowired(required = false)
    public DevTaskCommandService(
            DevTaskRepository devTaskRepository,
            RequirementRepository requirementRepository,
            UserRepository userRepository,
            RequirementCommandService requirementCommandService) {
        this.devTaskRepository = Objects.requireNonNull(devTaskRepository, "devTaskRepository must not be null");
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.userRepository = userRepository;
        this.requirementCommandService = Objects.requireNonNull(
                requirementCommandService, "requirementCommandService must not be null");
    }

    /**
     * Dispatches a pending task to an existing user and starts requirement development in the
     * same transaction. The linked requirement must still be APPROVED. The requirement-side
     * transition runs before the task write so a failing requirement command (e.g. a stale
     * version) leaves the task untouched, shrinking the cross-aggregate failure window.
     */
    @Transactional
    public DevTask assign(DevTaskId taskId, String assigneeUsername, String dispatcherUsername, long expectedVersion) {
        DevTaskId targetTaskId = Objects.requireNonNull(taskId, "taskId must not be null");
        String assignee = requireUser(assigneeUsername);
        String dispatcher = required(dispatcherUsername, "dispatcherUsername");
        DevTask task = require(targetTaskId);
        task.requireExpectedVersion(expectedVersion);
        Requirement requirement = requireRequirement(task.requirementId());
        if (requirement.status() != RequirementStatus.APPROVED) {
            throw new TaskDomainException(
                    TaskErrorCode.TASK_REQUIREMENT_STATE_CONFLICT,
                    "linked requirement must be APPROVED before dispatch, but was " + requirement.status());
        }
        requirementCommandService.startDevelopment(requirement.id(), requirement.version());
        task.assign(assignee, dispatcher, stateMachine);
        devTaskRepository.save(task);
        return task;
    }

    /**
     * Hands a developing task in for acceptance; only the assigned owner may do this.
     */
    @Transactional
    public DevTask submitAcceptance(DevTaskId taskId, String operatorUsername, long expectedVersion) {
        DevTask task = require(Objects.requireNonNull(taskId, "taskId must not be null"));
        task.requireExpectedVersion(expectedVersion);
        task.submitAcceptance(operatorUsername, stateMachine);
        devTaskRepository.save(task);
        return task;
    }

    /**
     * Accepts a pending-acceptance task and completes the linked requirement in the same
     * transaction. The requirement must still be DEVELOPING. The requirement-side transition
     * runs before the task write so a failing requirement command leaves the task untouched,
     * shrinking the cross-aggregate failure window.
     */
    @Transactional
    public DevTask accept(DevTaskId taskId, String note, long expectedVersion) {
        DevTask task = require(Objects.requireNonNull(taskId, "taskId must not be null"));
        task.requireExpectedVersion(expectedVersion);
        Requirement requirement = requireRequirement(task.requirementId());
        if (requirement.status() != RequirementStatus.DEVELOPING) {
            throw new TaskDomainException(
                    TaskErrorCode.TASK_REQUIREMENT_STATE_CONFLICT,
                    "linked requirement must be DEVELOPING before acceptance, but was " + requirement.status());
        }
        requirementCommandService.complete(requirement.id(), requirement.version());
        task.accept(note, stateMachine);
        devTaskRepository.save(task);
        return task;
    }

    /**
     * Rejects a pending-acceptance task back to development; the requirement stays DEVELOPING.
     */
    @Transactional
    public DevTask reject(DevTaskId taskId, String note, long expectedVersion) {
        DevTask task = require(Objects.requireNonNull(taskId, "taskId must not be null"));
        task.requireExpectedVersion(expectedVersion);
        task.reject(note, stateMachine);
        devTaskRepository.save(task);
        return task;
    }

    private String requireUser(String assigneeUsername) {
        String assignee = required(assigneeUsername, "assigneeUsername");
        if (userRepository == null) {
            throw new TaskDomainException(TaskErrorCode.FORBIDDEN, "user directory is unavailable");
        }
        userRepository.findByUsername(assignee)
                .orElseThrow(() -> new TaskDomainException(
                        TaskErrorCode.TASK_NOT_FOUND, "assignee user was not found: " + assignee));
        return assignee;
    }

    private Requirement requireRequirement(RequirementId requirementId) {
        return requirementRepository.findById(requirementId)
                .orElseThrow(() -> new TaskDomainException(
                        TaskErrorCode.TASK_NOT_FOUND, "linked requirement was not found"));
    }

    private DevTask require(DevTaskId taskId) {
        return devTaskRepository.findById(taskId)
                .orElseThrow(() -> new TaskDomainException(
                        TaskErrorCode.TASK_NOT_FOUND, "dev task was not found"));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
