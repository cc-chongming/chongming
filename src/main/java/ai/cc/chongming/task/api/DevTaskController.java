package ai.cc.chongming.task.api;

import ai.cc.chongming.auth.api.AuthJwtFilter;
import ai.cc.chongming.auth.api.PrincipalAccessor;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.domain.UserRole;
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
    private final PrincipalAccessor principalAccessor = new PrincipalAccessor();

    public DevTaskController(
            DevTaskCommandService commandService,
            DevTaskQueryService queryService,
            DevTaskProvisioningListener provisioningListener) {
        this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
        this.provisioningListener = Objects.requireNonNull(
                provisioningListener, "provisioningListener must not be null");
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
        requireAdmin(request);
        commandService.accept(new DevTaskId(taskId), body.note(), body.expectedVersion());
        return queryService.findById(new DevTaskId(taskId));
    }

    @PostMapping("/{taskId}/reject")
    public DevTaskView reject(
            @PathVariable("taskId") UUID taskId,
            @Valid @RequestBody AcceptanceCommand body,
            HttpServletRequest request) {
        requireAdmin(request);
        commandService.reject(new DevTaskId(taskId), body.note(), body.expectedVersion());
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
     * Acceptance command body carrying an optional note plus the optimistic-lock version.
     *
     * @author wangli
     */
    public record AcceptanceCommand(
            @Size(max = 512) String note,
            long expectedVersion) {
    }

    /**
     * Reconciliation outcome.
     *
     * @author wangli
     */
    public record ReconcileResponse(long created) {
    }
}
