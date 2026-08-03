package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.DashboardQueryService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AIREVIEW-PLAN-021#4] Platform home read endpoint.
 *
 * @author zyj
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;

    public DashboardController(DashboardQueryService dashboardQueryService) {
        this.dashboardQueryService = Objects.requireNonNull(dashboardQueryService, "dashboardQueryService must not be null");
    }

    @GetMapping
    public DashboardQueryService.DashboardView dashboard() {
        return dashboardQueryService.getDashboard();
    }
}
