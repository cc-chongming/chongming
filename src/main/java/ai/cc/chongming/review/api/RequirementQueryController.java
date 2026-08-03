package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.RequirementQueryService;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AIREVIEW-PLAN-021#2] Exposes paged requirement reads under the new API namespace.
 *
 * @author zyj
 */
@RestController
@RequestMapping("/api/requirements")
public class RequirementQueryController {

    private final RequirementQueryService queryService;

    public RequirementQueryController(RequirementQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public RequirementQueryService.RequirementPage list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignee", required = false) String assignee,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return queryService.findPage(status, assignee, keyword, page, size);
    }

    @GetMapping("/{requirementId}")
    public RequirementQueryService.RequirementView detail(@PathVariable UUID requirementId) {
        return queryService.findById(new RequirementId(requirementId));
    }
}
