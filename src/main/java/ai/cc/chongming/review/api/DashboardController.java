package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.DashboardQueryService;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AIREVIEW-PLAN-021#4][AIREVIEW-PLAN-027] Platform home read endpoint. Requirement status
 * counts converge to the caller's visibility; administrators and demo profiles without
 * authentication keep the platform-wide totals.
 *
 * @author zyj
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;
    private final RequirementVisibilityResolver visibilityResolver;

    public DashboardController(DashboardQueryService dashboardQueryService) {
        this(dashboardQueryService, null);
    }

    @Autowired
    public DashboardController(DashboardQueryService dashboardQueryService, DevTaskRepository devTaskRepository) {
        this.dashboardQueryService = Objects.requireNonNull(dashboardQueryService, "dashboardQueryService must not be null");
        this.visibilityResolver = new RequirementVisibilityResolver(devTaskRepository);
    }

    @GetMapping
    public DashboardQueryService.DashboardView dashboard(HttpServletRequest request) {
        return dashboardQueryService.getDashboard(visibilityResolver.visibilityFor(request));
    }
}
