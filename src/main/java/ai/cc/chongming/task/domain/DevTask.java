package ai.cc.chongming.task.domain;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.DevTaskTypes.HandoffEntry;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.domain.protocol.DevTaskStateMachine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns one development task dispatched for an approved requirement. Each approved
 * requirement produces at most one task; the task walks PENDING_ASSIGN → DEVELOPING →
 * PENDING_ACCEPTANCE → DONE, with rejection returning it to DEVELOPING.
 *
 * @author wangli
 */
public final class DevTask {

    private static final int ACCEPTANCE_NOTE_MAX_LENGTH = 512;

    private final DevTaskId taskId;
    private final RequirementId requirementId;
    private final ReviewId reviewId;
    private final String title;
    private DevTaskStatus status;
    private String assigneeUsername;
    private String dispatcherUsername;
    private String acceptanceNote;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;
    private String requirementTitle;
    private String assigneeDisplayName;
    private String currentHolderUsername;
    private final List<HandoffEntry> handoffHistory = new ArrayList<>();

    private DevTask(
            DevTaskId taskId,
            RequirementId requirementId,
            ReviewId reviewId,
            String title,
            DevTaskStatus status,
            String assigneeUsername,
            String dispatcherUsername,
            String acceptanceNote,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.requirementId = Objects.requireNonNull(requirementId, "requirementId must not be null");
        this.reviewId = reviewId;
        this.title = required(title, "title");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.assigneeUsername = normalizeOptional(assigneeUsername);
        this.dispatcherUsername = normalizeOptional(dispatcherUsername);
        this.acceptanceNote = normalizeNote(acceptanceNote);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
    }

    public static DevTask draft(DevTaskId taskId, RequirementId requirementId, ReviewId reviewId, String title) {
        Instant now = Instant.now();
        return new DevTask(
                taskId,
                requirementId,
                reviewId,
                title,
                DevTaskStatus.PENDING_ASSIGN,
                null,
                null,
                null,
                now,
                now,
                0L);
    }

    public static DevTask restore(
            DevTaskId taskId,
            RequirementId requirementId,
            ReviewId reviewId,
            String title,
            DevTaskStatus status,
            String assigneeUsername,
            String dispatcherUsername,
            String acceptanceNote,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return restore(taskId, requirementId, reviewId, title, status, assigneeUsername, dispatcherUsername,
                acceptanceNote, createdAt, updatedAt, version, assigneeUsername, List.of());
    }

    /**
     * [AIREVIEW-PLAN-030] Restore carrying the handoff chain facts.
     */
    public static DevTask restore(
            DevTaskId taskId,
            RequirementId requirementId,
            ReviewId reviewId,
            String title,
            DevTaskStatus status,
            String assigneeUsername,
            String dispatcherUsername,
            String acceptanceNote,
            Instant createdAt,
            Instant updatedAt,
            long version,
            String currentHolderUsername,
            List<HandoffEntry> handoffHistory) {
        DevTask task = new DevTask(
                taskId,
                requirementId,
                reviewId,
                title,
                status,
                assigneeUsername,
                dispatcherUsername,
                acceptanceNote,
                createdAt,
                updatedAt,
                version);
        task.currentHolderUsername = normalizeOptional(currentHolderUsername);
        if (handoffHistory != null) {
            task.handoffHistory.addAll(handoffHistory);
        }
        return task;
    }

    public DevTaskId taskId() {
        return taskId;
    }

    public RequirementId requirementId() {
        return requirementId;
    }

    public ReviewId reviewId() {
        return reviewId;
    }

    public String title() {
        return title;
    }

    public DevTaskStatus status() {
        return status;
    }

    public String assigneeUsername() {
        return assigneeUsername;
    }

    public String dispatcherUsername() {
        return dispatcherUsername;
    }

    public String acceptanceNote() {
        return acceptanceNote;
    }

    /** [AIREVIEW-PLAN-030] Current holder; falls back to the assignee for pre-handoff tasks. */
    public String currentHolderUsername() {
        return currentHolderUsername != null ? currentHolderUsername : assigneeUsername;
    }

    /** [AIREVIEW-PLAN-030] Append-only handoff timeline. */
    public List<HandoffEntry> handoffHistory() {
        return List.copyOf(handoffHistory);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /**
     * Read-time enrichment only: the joined requirement title carried by page reads.
     * Never persisted on the dev_task table itself.
     */
    public String requirementTitle() {
        return requirementTitle;
    }

    public void withRequirementTitle(String requirementTitle) {
        this.requirementTitle = requirementTitle;
    }

    /**
     * Read-time enrichment only: the joined users.display_name for the assignee.
     * Never persisted on the dev_task table itself.
     */
    public String assigneeDisplayName() {
        return assigneeDisplayName;
    }

    public void withAssigneeDisplayName(String assigneeDisplayName) {
        this.assigneeDisplayName = assigneeDisplayName;
    }

    public void transitionTo(DevTaskStatus nextStatus, DevTaskStateMachine stateMachine) {
        status = Objects.requireNonNull(stateMachine, "stateMachine must not be null")
                .transition(status, Objects.requireNonNull(nextStatus, "nextStatus must not be null"));
        touch();
    }

    public void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new TaskDomainException(
                    TaskErrorCode.VERSION_CONFLICT,
                    "expectedVersion does not match dev task version");
        }
    }

    public void assign(String assigneeUsername, String dispatcherUsername, DevTaskStateMachine stateMachine) {
        String assignee = required(assigneeUsername, "assigneeUsername");
        String dispatcher = required(dispatcherUsername, "dispatcherUsername");
        transitionTo(DevTaskStatus.DEVELOPING, stateMachine);
        this.assigneeUsername = assignee;
        this.dispatcherUsername = dispatcher;
        this.currentHolderUsername = assignee;
    }

    /**
     * [AIREVIEW-PLAN-030] Directed holder change while developing; appends an immutable
     * handoff entry and re-points the assignee so legacy visibility keeps working.
     */
    public void handoff(String toUsername, String note, DevTaskStateMachine stateMachine) {
        Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        if (status != DevTaskStatus.DEVELOPING) {
            throw new TaskDomainException(
                    TaskErrorCode.ILLEGAL_TASK_TRANSITION,
                    "handoff requires DEVELOPING, but was " + status);
        }
        String to = required(toUsername, "toUsername");
        String from = currentHolderUsername != null ? currentHolderUsername : assigneeUsername;
        handoffHistory.add(new HandoffEntry(handoffHistory.size() + 1, from, to, normalizeNote(note), Instant.now()));
        this.currentHolderUsername = to;
        this.assigneeUsername = to;
        touch();
    }

    /** [AIREVIEW-PLAN-030] Pauses a developing task; the note carries the blocking reason. */
    public void pause(String note, DevTaskStateMachine stateMachine) {
        transitionTo(DevTaskStatus.PAUSED, stateMachine);
        this.acceptanceNote = normalizeNote(note);
    }

    /** [AIREVIEW-PLAN-030] Resumes a paused task back to development. */
    public void resume(String note, DevTaskStateMachine stateMachine) {
        transitionTo(DevTaskStatus.DEVELOPING, stateMachine);
        this.acceptanceNote = normalizeNote(note);
    }

    /** [AIREVIEW-PLAN-030] Terminally closes the task without completion. */
    public void cancel(String note, DevTaskStateMachine stateMachine) {
        transitionTo(DevTaskStatus.CANCELLED, stateMachine);
        this.acceptanceNote = normalizeNote(note);
    }

    /**
     * Only the assigned owner may hand the task in for acceptance.
     */
    public void submitAcceptance(String operatorUsername, DevTaskStateMachine stateMachine) {
        String operator = required(operatorUsername, "operatorUsername");
        if (!operator.equals(assigneeUsername)) {
            throw new TaskDomainException(
                    TaskErrorCode.FORBIDDEN,
                    "only the assigned owner can submit the task for acceptance");
        }
        transitionTo(DevTaskStatus.PENDING_ACCEPTANCE, stateMachine);
    }

    public void accept(String note, DevTaskStateMachine stateMachine) {
        transitionTo(DevTaskStatus.DONE, stateMachine);
        this.acceptanceNote = normalizeNote(note);
    }

    public void reject(String note, DevTaskStateMachine stateMachine) {
        transitionTo(DevTaskStatus.DEVELOPING, stateMachine);
        this.acceptanceNote = normalizeNote(note);
    }

    private void touch() {
        updatedAt = Instant.now();
        version++;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeNote(String value) {
        String normalized = normalizeOptional(value);
        if (normalized != null && normalized.length() > ACCEPTANCE_NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("acceptanceNote must not exceed " + ACCEPTANCE_NOTE_MAX_LENGTH + " characters");
        }
        return normalized;
    }
}
