package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.RequirementQueryService;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AIREVIEW-PLAN-021#2][AIREVIEW-PLAN-027] Exposes paged requirement reads under the new API
 * namespace. Reads are scoped by the caller's visibility: administrators and demo profiles
 * without authentication see everything, every other role only sees requirements it created or
 * owns a dev task for.
 *
 * @author zyj
 */
@RestController
@RequestMapping("/api/requirements")
public class RequirementQueryController {

    private final RequirementQueryService queryService;
    private final RequirementVisibilityResolver visibilityResolver;

    public RequirementQueryController(RequirementQueryService queryService) {
        this(queryService, null);
    }

    @Autowired
    public RequirementQueryController(RequirementQueryService queryService, DevTaskRepository devTaskRepository) {
        this.queryService = queryService;
        this.visibilityResolver = new RequirementVisibilityResolver(devTaskRepository);
    }

    @GetMapping
    public RequirementQueryService.RequirementPage list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignee", required = false) String assignee,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        return queryService.findPage(status, assignee, keyword, page, size, visibilityResolver.visibilityFor(request));
    }

    @GetMapping("/{requirementId}")
    public RequirementQueryService.RequirementView detail(
            @PathVariable UUID requirementId, HttpServletRequest request) {
        return queryService.findById(new RequirementId(requirementId), visibilityResolver.visibilityFor(request));
    }
}
