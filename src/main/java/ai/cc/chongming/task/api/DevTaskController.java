package ai.cc.chongming.task.api;

import ai.cc.chongming.auth.api.AuthJwtFilter;
import ai.cc.chongming.auth.api.PrincipalAccessor;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.auth.domain.UserRole;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.task.application.DevTaskCommandService;
import ai.cc.chongming.task.application.DevTaskProvisioningListener;
import ai.cc.chongming.task.application.DevTaskQueryService;
import ai.cc.chongming.task.application.DevTaskQueryService.DevTaskView;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for development-task dispatch and acceptance. Role checks read the principal
 * attribute stored by {@link AuthJwtFilter}: administrators dispatch, accept, reject and
 * reconcile; only the assigned owner submits a task for acceptance.
 *
 * @author wangli
 */
@Validated
@RestController
@RequestMapping("/api/tasks")
public class DevTaskController {

    private final DevTaskCommandService commandService;
    private final DevTaskQueryService queryService;
    private final DevTaskProvisioningListener provisioningListener;
    private final RequirementRepository requirementRepository;
    private final UserRepository userRepository;
    private final PrincipalAccessor principalAccessor = new PrincipalAccessor();

    public DevTaskController(
            DevTaskCommandService commandService,
            DevTaskQueryService queryService,
            DevTaskProvisioningListener provisioningListener) {
        this(commandService, queryService, provisioningListener, null, null);
    }

    public DevTaskController(
            DevTaskCommandService commandService,
            DevTaskQueryService queryService,
            DevTaskProvisioningListener provisioningListener,
            RequirementRepository requirementRepository) {
        this(commandService, queryService, provisioningListener, requirementRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DevTaskController(
            DevTaskCommandService commandService,
            DevTaskQueryService queryService,
            DevTaskProvisioningListener provisioningListener,
            RequirementRepository requirementRepository,
            UserRepository userRepository) {
        this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
        this.provisioningListener = Objects.requireNonNull(
                provisioningListener, "provisioningListener must not be null");
        this.requirementRepository = requirementRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public DevTaskQueryService.TaskPage list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "assignee", required = false) String assignee,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "requirementId", required = false) String requirementId,
            @RequestParam(name = "mine", required = false, defaultValue = "false") boolean mine,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size,
            HttpServletRequest request) {
        AuthPrincipal principal = requirePrincipal(request);
        String effectiveAssignee = mine ? principal.username() : assignee;
        return queryService.findPage(status, effectiveAssignee, keyword, requirementId, page, size);
    }

    @GetMapping("/{taskId}")
    public DevTaskView get(@PathVariable("taskId") UUID taskId, HttpServletRequest request) {
        requirePrincipal(request);
        return queryService.findById(new DevTaskId(taskId));
    }

    @PostMapping("/{taskId}/assign")
    public DevTaskView assign(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody AssignRequest body,
            HttpServletRequest request) {
        AuthPrincipal principal = requireAdmin(request);
        commandService.assign(new DevTaskId(taskId), body.assigneeUsername(), principal.username(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    @PostMapping("/{taskId}/submit-acceptance")
    public DevTaskView submitAcceptance(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody VersionedCommand body,
            HttpServletRequest request) {
        AuthPrincipal principal = requirePrincipal(request);
        commandService.submitAcceptance(new DevTaskId(taskId), principal.username(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    @PostMapping("/{taskId}/accept")
    public DevTaskView accept(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody AcceptanceCommand body,
            HttpServletRequest request) {
        requireAdminOrCreator(request, new DevTaskId(taskId));
        commandService.accept(new DevTaskId(taskId), body.note(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    @PostMapping("/{taskId}/reject")
    public DevTaskView reject(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody AcceptanceCommand body,
            HttpServletRequest request) {
        requireAdminOrCreator(request, new DevTaskId(taskId));
        commandService.reject(new DevTaskId(taskId), body.note(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    /** [AIREVIEW-PLAN-030] Directed holder change; only the current holder or ADMIN. */
    @PostMapping("/{taskId}/handoff")
    public DevTaskView handoff(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody HandoffRequest body,
            HttpServletRequest request) {
        requireAdminOrHolder(request, new DevTaskId(taskId));
        // [AIREVIEW-PLAN-031#1] The handoff target must be a real account; the UI offers the
        // non-admin directory from /assignable-users.
        if (userRepository != null && userRepository.findByUsername(body.toUsername().trim()).isEmpty()) {
            throw new TaskDomainException(TaskErrorCode.USER_NOT_FOUND, "流转目标用户不存在");
        }
        commandService.handoff(new DevTaskId(taskId), body.toUsername(), body.note(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    /**
     * [AIREVIEW-PLAN-031#1] Non-admin account directory for handoff target selection. Any
     * authenticated participant may read it; administrators stay out of the selectable pool.
     */
    @GetMapping("/assignable-users")
    public List<AssignableUser> assignableUsers(HttpServletRequest request) {
        requirePrincipal(request);
        if (userRepository == null) {
            throw new TaskDomainException(TaskErrorCode.FORBIDDEN, "用户目录当前不可用");
        }
        return userRepository.findAll().stream()
                .filter(user -> !UserRole.parse(user.role()).viewsAllRequirements())
                .map(user -> new AssignableUser(user.username(), user.displayName()))
                .toList();
    }

    /** [AIREVIEW-PLAN-030] Pauses a developing task with a mandatory blocking reason. */
    @PostMapping("/{taskId}/pause")
    public DevTaskView pause(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody FlowCommand body,
            HttpServletRequest request) {
        requireAdminOrHolder(request, new DevTaskId(taskId));
        commandService.pause(new DevTaskId(taskId), body.note(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    /** [AIREVIEW-PLAN-030] Resumes a paused task. */
    @PostMapping("/{taskId}/resume")
    public DevTaskView resume(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody FlowCommand body,
            HttpServletRequest request) {
        requireAdminOrHolder(request, new DevTaskId(taskId));
        commandService.resume(new DevTaskId(taskId), body.note(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    /** [AIREVIEW-PLAN-030] Terminally closes the task; ADMIN or the requirement creator. */
    @PostMapping("/{taskId}/cancel")
    public DevTaskView cancel(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody FlowCommand body,
            HttpServletRequest request) {
        requireAdminOrCreator(request, new DevTaskId(taskId));
        commandService.cancel(new DevTaskId(taskId), body.note(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    @PostMapping("/reconcile")
    public ReconcileResponse reconcile(HttpServletRequest request) {
        requireAdmin(request);
        return new ReconcileResponse(provisioningListener.reconcile());
    }

    private AuthPrincipal requirePrincipal(HttpServletRequest request) {
        // [AIREVIEW-PLAN-027] Shared attribute read lives in PrincipalAccessor; task endpoints
        // keep their historical 403 contract for missing principals.
        return principalAccessor.requirePrincipal(request)
                .orElseThrow(() -> new TaskDomainException(TaskErrorCode.FORBIDDEN, "当前请求未携带有效的认证凭据"));
    }

    private AuthPrincipal requireAdmin(HttpServletRequest request) {
        AuthPrincipal principal = requirePrincipal(request);
        // [AIREVIEW-PLAN-027] Legacy USER or unknown roles parse to developer-level semantics and
        // never pass the administrator gate.
        if (!UserRole.parse(principal.role()).viewsAllRequirements()) {
            throw new TaskDomainException(TaskErrorCode.FORBIDDEN, "仅管理员可执行该任务操作");
        }
        return principal;
    }

    /**
     * [AIREVIEW-PLAN-030] Acceptance/cancellation belong to the requirement creator (the person
     * who raised and reviewed the requirement) or an administrator.
     */
    private AuthPrincipal requireAdminOrCreator(HttpServletRequest request, DevTaskId taskId) {
        AuthPrincipal principal = requirePrincipal(request);
        if (UserRole.parse(principal.role()).viewsAllRequirements()) {
            return principal;
        }
        DevTaskView view = queryService.findById(taskId);
        if (requirementRepository != null
                && requirementRepository.findById(new RequirementId(view.requirementId()))
                        .map(requirement -> principal.username().equals(requirement.creatorId()))
                        .orElse(false)) {
            return principal;
        }
        throw new TaskDomainException(TaskErrorCode.FORBIDDEN, "仅管理员或需求提出人可执行该任务操作");
    }

    /** [AIREVIEW-PLAN-030] Flow commands belong to the current holder or an administrator. */
    private AuthPrincipal requireAdminOrHolder(HttpServletRequest request, DevTaskId taskId) {
        AuthPrincipal principal = requirePrincipal(request);
        if (UserRole.parse(principal.role()).viewsAllRequirements()) {
            return principal;
        }
        DevTaskView view = queryService.findById(taskId);
        String holder = view.currentHolderUsername() != null
                ? view.currentHolderUsername() : view.assigneeUsername();
        if (principal.username().equals(holder)) {
            return principal;
        }
        throw new TaskDomainException(TaskErrorCode.FORBIDDEN, "仅当前持有人或管理员可执行该任务操作");
    }

    /**
     * Dispatch command body.
     *
     * @author wangli
     */
    public record AssignRequest(
            @NotBlank @Size(max = 64) String assigneeUsername,
            long expectedVersion) {
    }

    /**
     * Command body carrying only the optimistic-lock version.
     *
     * @author wangli
     */
    public record VersionedCommand(long expectedVersion) {
    }

    /**
     * [AIREVIEW-PLAN-030] Handoff command body: target user plus optional note.
     *
     * @author wangli
     */
    public record HandoffRequest(
            @NotBlank @Size(max = 64) String toUsername,
            @Size(max = 512) String note,
            long expectedVersion) {
    }

    /**
     * [AIREVIEW-PLAN-030] Pause/resume/cancel command body carrying the flow note.
     *
     * @author wangli
     */
    public record FlowCommand(
            @Size(max = 512) String note,
            long expectedVersion) {
    }

    /**
     * Acceptance command body carrying an optional note plus the optimistic-lock version.
     *
     * @author wangli
     */
    public record AcceptanceCommand(
            @Size(max = 512) String note,
            long expectedVersion) {
    }

    /**
     * [AIREVIEW-PLAN-031#1] Selectable handoff target: username plus display name, never ADMIN.
     *
     * @author wangli
     */
    public record AssignableUser(String username, String displayName) {
    }

    /**
     * Reconciliation outcome.
     *
     * @author wangli
     */
    public record ReconcileResponse(long created) {
    }
}
