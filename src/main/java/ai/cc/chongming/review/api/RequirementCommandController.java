package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.application.RequirementReviewLaunchException;
import ai.cc.chongming.review.application.RequirementReviewLaunchService;
import ai.cc.chongming.review.application.RequirementQueryService.RequirementView;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
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
 * [AIREVIEW-PLAN-021#2][AIREVIEW-PLAN-023#3] Exposes requirement lifecycle commands and draft review launch.
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

    public RequirementCommandController(RequirementCommandService commandService) {
        this(commandService, null, new ObjectMapper());
    }

    @Autowired
    public RequirementCommandController(
            RequirementCommandService commandService,
            RequirementReviewLaunchService launchService,
            ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.launchService = launchService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<RequirementView> create(@Valid @RequestBody CreateRequirementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(RequirementView.from(commandService.create(
                new RequirementCommandService.CreateRequirementCommand(
                        request.title(), request.description(), request.assigneeId(), request.repositoryPath(), request.priority()))));
    }

    @PutMapping("/{requirementId}")
    public RequirementView revise(
            @PathVariable UUID requirementId, @Valid @RequestBody ReviseRequirementRequest request) {
        return RequirementView.from(commandService.revise(
                new RequirementId(requirementId),
                new RequirementCommandService.ReviseRequirementCommand(
                        request.title(),
                        request.description(),
                        request.assigneeId(),
                        request.repositoryPath(),
                        request.priority(),
                        request.expectedVersion())));
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
            @RequestParam("repositoryPath") @NotBlank String repositoryPath,
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
        return RequirementView.from(commandService.startDevelopment(new RequirementId(requirementId), request.expectedVersion()));
    }

    @PostMapping("/{requirementId}/complete")
    public RequirementView complete(@PathVariable UUID requirementId, @Valid @RequestBody VersionedCommand request) {
        return RequirementView.from(commandService.complete(new RequirementId(requirementId), request.expectedVersion()));
    }

    @PostMapping("/{requirementId}/cancel")
    public RequirementView cancel(@PathVariable UUID requirementId, @Valid @RequestBody VersionedCommand request) {
        return RequirementView.from(commandService.cancel(new RequirementId(requirementId), request.expectedVersion()));
    }

    @DeleteMapping("/{requirementId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID requirementId,
            @org.springframework.web.bind.annotation.RequestParam @Min(0) long expectedVersion) {
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
            String priority) {
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
            @Min(0) long expectedVersion) {
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
