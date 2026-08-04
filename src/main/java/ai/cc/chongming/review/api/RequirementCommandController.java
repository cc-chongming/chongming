package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.application.RequirementQueryService.RequirementView;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AIREVIEW-PLAN-021#2] Exposes requirement lifecycle commands without changing review command contracts.
 *
 * @author zyj
 */
@Validated
@RestController
@RequestMapping("/api/requirements")
public class RequirementCommandController {

    private final RequirementCommandService commandService;

    public RequirementCommandController(RequirementCommandService commandService) {
        this.commandService = commandService;
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
}
