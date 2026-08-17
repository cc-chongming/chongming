package ai.cc.chongming.review.api;

import ai.cc.chongming.auth.api.PrincipalAccessor;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.domain.UserRole;
import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.application.RequirementReviewLaunchException;
import ai.cc.chongming.review.application.RequirementReviewLaunchService;
import ai.cc.chongming.review.application.RequirementQueryService;
import ai.cc.chongming.review.application.RequirementQueryService.RequirementView;
import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.task.application.TaskFlowGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * [AIREVIEW-PLAN-021#2][AIREVIEW-PLAN-023#3][AIREVIEW-PLAN-027] Exposes requirement lifecycle
 * commands and draft review launch. Authenticated callers are gated by role on create and by
 * ownership (creator or administrator) on revise/delete; requests without a principal keep the
 * historical open behaviour for demo/test profiles.
 *
 * @author zyj
 */
@Validated
@RestController
@RequestMapping("/api/requirements")
public class RequirementCommandController {

    private final RequirementCommandService commandService;
    private final RequirementReviewLaunchService launchService;
    private final ObjectMapper objectMapper;
    private final TaskFlowGuard taskFlowGuard;
    private final RequirementQueryService queryService;
    private final PrincipalAccessor principalAccessor = new PrincipalAccessor();

    public RequirementCommandController(RequirementCommandService commandService) {
        this(commandService, null, new ObjectMapper(), null, null);
    }

    public RequirementCommandController(
            RequirementCommandService commandService,
            RequirementReviewLaunchService launchService,
            ObjectMapper objectMapper) {
        this(commandService, launchService, objectMapper, null, null);
    }

    public RequirementCommandController(
            RequirementCommandService commandService,
            RequirementReviewLaunchService launchService,
            ObjectMapper objectMapper,
            TaskFlowGuard taskFlowGuard) {
        this(commandService, launchService, objectMapper, taskFlowGuard, null);
    }

    @Autowired
    public RequirementCommandController(
            RequirementCommandService commandService,
            RequirementReviewLaunchService launchService,
            ObjectMapper objectMapper,
            TaskFlowGuard taskFlowGuard,
            RequirementQueryService queryService) {
        this.commandService = commandService;
        this.launchService = launchService;
        this.objectMapper = objectMapper;
        this.taskFlowGuard = taskFlowGuard;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<RequirementView> create(
            @Valid @RequestBody CreateRequirementRequest request, HttpServletRequest httpRequest) {
        // [AIREVIEW-PLAN-027] Authenticated callers need a creator-capable role; the principal
        // username becomes the requirement creator. Demo profiles without a principal keep the
        // historical identity-provider fallback.
        Optional<AuthPrincipal> principal = principalAccessor.requirePrincipal(httpRequest);
        String creatorUsername = null;
        if (principal.isPresent()) {
            AuthPrincipal authPrincipal = principal.get();
            if (!UserRole.parse(authPrincipal.role()).canCreateRequirement()) {
                throw new RequirementDomainException(RequirementErrorCode.FORBIDDEN, "当前角色无权新建需求");
            }
            creatorUsername = authPrincipal.username();
        }
        RequirementCommandService.RemoteSourceCommand remote = toRemoteCommand(request.remote());
        requireSingleRepositoryBinding(request.repositoryPath(), remote);
        return ResponseEntity.status(HttpStatus.CREATED).body(RequirementView.from(commandService.create(
                new RequirementCommandService.CreateRequirementCommand(
                        request.title(),
                        request.description(),
                        request.assigneeId(),
                        request.repositoryPath(),
                        request.priority(),
                        creatorUsername,
                        remote))));
    }

    @PutMapping("/{requirementId}")
    public RequirementView revise(
            @PathVariable UUID requirementId,
            @Valid @RequestBody ReviseRequirementRequest request,
            HttpServletRequest httpRequest) {
        requireOwnership(new RequirementId(requirementId), httpRequest);
        RequirementCommandService.RemoteSourceCommand remote = toRemoteCommand(request.remote());
        requireSingleRepositoryBinding(request.repositoryPath(), remote);
        return RequirementView.from(commandService.revise(
                new RequirementId(requirementId),
                new RequirementCommandService.ReviseRequirementCommand(
                        request.title(),
                        request.description(),
                        request.assigneeId(),
                        request.repositoryPath(),
                        request.priority(),
                        request.expectedVersion(),
                        remote)));
    }

    @PostMapping("/{requirementId}/submit")
    public RequirementView submitForReview(
            @PathVariable UUID requirementId, @Valid @RequestBody SubmitForReviewRequest request) {
        return RequirementView.from(commandService.submitForReview(
                new RequirementId(requirementId), new ReviewId(request.reviewId()), request.expectedVersion()));
    }

    /**
     * [AIREVIEW-PLAN-023#3] Accepts one idempotent multipart command for intake, binding and start.
     */
    @PostMapping(value = "/{requirementId}/reviews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RequirementReviewLaunchService.LaunchResult> launchReview(
            @PathVariable UUID requirementId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestPart("requirementFile") MultipartFile requirementFile,
            @RequestParam(value = "repositoryPath", required = false) String repositoryPath,
            @RequestParam(value = "branch", required = false) String branch,
            @RequestParam(value = "commit", required = false) String commit,
            @RequestParam("submitter") @NotBlank String submitter,
            @RequestParam("publicTasks") String publicTasks,
            @RequestParam("changeReason") @NotBlank String changeReason,
            @RequestParam("initialMessage") @NotBlank String initialMessage,
            @RequestParam("expectedVersion") @Min(0) long expectedVersion) {
        if (launchService == null) {
            throw new IllegalStateException("requirement review launch service is unavailable");
        }
        String effectiveTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        List<String> tasks = parsePublicTasks(publicTasks);
        RequirementReviewLaunchService.LaunchResult result = launchService.launch(
                new RequirementId(requirementId),
                new RequirementReviewLaunchService.LaunchCommand(
                        requirementFile,
                        repositoryPath,
                        branch,
                        commit,
                        submitter,
                        expectedVersion,
                        idempotencyKey,
                        effectiveTraceId,
                        tasks,
                        changeReason,
                        initialMessage));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping("/{requirementId}/start-development")
    public RequirementView startDevelopment(
            @PathVariable UUID requirementId, @Valid @RequestBody VersionedCommand request) {
        requireNoActiveTask(requirementId);
        return RequirementView.from(commandService.startDevelopment(new RequirementId(requirementId), request.expectedVersion()));
    }

    @PostMapping("/{requirementId}/complete")
    public RequirementView complete(@PathVariable UUID requirementId, @Valid @RequestBody VersionedCommand request) {
        requireNoActiveTask(requirementId);
        return RequirementView.from(commandService.complete(new RequirementId(requirementId), request.expectedVersion()));
    }

    @PostMapping("/{requirementId}/cancel")
    public RequirementView cancel(@PathVariable UUID requirementId, @Valid @RequestBody VersionedCommand request) {
        requireNoActiveTask(requirementId);
        return RequirementView.from(commandService.cancel(new RequirementId(requirementId), request.expectedVersion()));
    }

    @DeleteMapping("/{requirementId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID requirementId,
            @org.springframework.web.bind.annotation.RequestParam @Min(0) long expectedVersion,
            HttpServletRequest httpRequest) {
        requireOwnership(new RequirementId(requirementId), httpRequest);
        commandService.delete(new RequirementId(requirementId), expectedVersion);
        return ResponseEntity.noContent().build();
    }

    /**
     * @author zyj
     */
    public record CreateRequirementRequest(
            @NotBlank String title,
            String description,
            String assigneeId,
            String repositoryPath,
            String priority,
            @Valid RemoteRequest remote) {
    }

    /**
     * @author zyj
     */
    public record ReviseRequirementRequest(
            @NotBlank String title,
            String description,
            String assigneeId,
            String repositoryPath,
            String priority,
            @Min(0) long expectedVersion,
            @Valid RemoteRequest remote) {
    }

    /**
     * [AIREVIEW-PLAN-029] Online repository binding supplied at creation or revision; the token
     * is write-only and never echoed by any response.
     *
     * @author wangli
     */
    public record RemoteRequest(
            @Size(max = 512) String url,
            @Size(max = 128) String ref,
            @Size(max = 512) String token) {
    }

    /**
     * @author zyj
     */
    public record SubmitForReviewRequest(@NotNull java.util.UUID reviewId, @Min(0) long expectedVersion) {
    }

    /**
     * @author zyj
     */
    public record VersionedCommand(@Min(0) long expectedVersion) {
    }

    /**
     * Blocks manual lifecycle commands while the requirement still owns a non-DONE dev task;
     * skipped when the guard is absent (legacy constructor used by older tests).
     */
    private void requireNoActiveTask(UUID requirementId) {
        if (taskFlowGuard != null) {
            taskFlowGuard.requireNoActiveTask(new RequirementId(requirementId));
        }
    }

    /**
     * [AIREVIEW-PLAN-027] Ownership gate for revise/delete: without a principal the historical
     * open behaviour applies; otherwise only the creator or an administrator may proceed. Hidden
     * requirements surface 404 through the query lookup so existence is never leaked.
     */
    private void requireOwnership(RequirementId requirementId, HttpServletRequest request) {
        Optional<AuthPrincipal> principal = principalAccessor.requirePrincipal(request);
        if (principal.isEmpty()) {
            return;
        }
        AuthPrincipal authPrincipal = principal.get();
        if (UserRole.parse(authPrincipal.role()).viewsAllRequirements()) {
            return;
        }
        if (queryService == null) {
            throw new RequirementDomainException(
                    RequirementErrorCode.FORBIDDEN, "仅需求创建者或管理员可执行该操作");
        }
        RequirementView view = queryService.findById(requirementId);
        if (!authPrincipal.username().equals(view.creatorId())) {
            throw new RequirementDomainException(
                    RequirementErrorCode.FORBIDDEN, "仅需求创建者或管理员可执行该操作");
        }
    }

    /**
     * [AIREVIEW-PLAN-029] The configured-repository identity and the online source are mutually
     * exclusive, and a remote binding must at least name its URL.
     */
    private void requireSingleRepositoryBinding(
            String repositoryPath, RequirementCommandService.RemoteSourceCommand remote) {
        boolean hasConfigured = repositoryPath != null && !repositoryPath.isBlank();
        boolean hasRemote = remote != null && remote.url() != null && !remote.url().isBlank();
        if (hasConfigured && hasRemote) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID, "配置仓库与线上仓库只能二选一");
        }
        if (remote != null && !hasRemote) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID, "线上仓库必须填写仓库地址");
        }
    }

    private RequirementCommandService.RemoteSourceCommand toRemoteCommand(RemoteRequest request) {
        if (request == null) {
            return null;
        }
        return new RequirementCommandService.RemoteSourceCommand(request.url(), request.ref(), request.token());
    }

    private List<String> parsePublicTasks(String publicTasks) {
        try {
            List<String> tasks = objectMapper.readValue(publicTasks, new TypeReference<>() {
            });
            if (tasks == null || tasks.isEmpty() || tasks.stream().anyMatch(task -> task == null || task.isBlank())) {
                throw RequirementReviewLaunchException.invalidPublicTasks();
            }
            return tasks;
        } catch (JsonProcessingException exception) {
            throw RequirementReviewLaunchException.invalidPublicTasks();
        }
    }
}
