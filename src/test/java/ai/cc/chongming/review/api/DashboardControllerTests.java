package ai.cc.chongming.review.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.auth.api.AuthJwtFilter;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.review.application.DashboardQueryService;
import ai.cc.chongming.review.application.DashboardQueryService.DashboardView;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementVisibility;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * [AIREVIEW-PLAN-027] HTTP contract tests for the dashboard endpoint's viewer-scoped status
 * counts: administrators and demo requests without a principal keep the platform-wide totals,
 * every other role converges to its visibility scope.
 *
 * @author wangli
 */
class DashboardControllerTests {

    private DashboardQueryService dashboardQueryService;
    private DevTaskRepository devTaskRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dashboardQueryService = mock(DashboardQueryService.class);
        devTaskRepository = mock(DevTaskRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardController(dashboardQueryService, devTaskRepository))
                .build();
    }

    @Test
    void administratorDashboardKeepsPlatformWideCounts() throws Exception {
        when(dashboardQueryService.getDashboard(isNull())).thenReturn(view(Map.of("DRAFT", 3L)));

        mockMvc.perform(get("/api/dashboard")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("admin", "管理员", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementStatusCounts.DRAFT").value(3));

        verify(devTaskRepository, never()).findRequirementIdsByAssignee(any());
    }

    @Test
    void developerDashboardConvergesCountsToTheVisibilityScope() throws Exception {
        RequirementId assignedId = new RequirementId(UUID.randomUUID());
        when(devTaskRepository.findRequirementIdsByAssignee("dev-zhang")).thenReturn(Set.of(assignedId));
        when(dashboardQueryService.getDashboard(any(RequirementVisibility.class))).thenReturn(view(Map.of("DRAFT", 2L)));

        mockMvc.perform(get("/api/dashboard")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementStatusCounts.DRAFT").value(2));

        ArgumentCaptor<RequirementVisibility> visibility = ArgumentCaptor.forClass(RequirementVisibility.class);
        verify(dashboardQueryService).getDashboard(visibility.capture());
        assertThat(visibility.getValue().viewerUsername()).isEqualTo("dev-zhang");
        assertThat(visibility.getValue().assignedRequirementIds()).containsExactly(assignedId);
    }

    @Test
    void dashboardWithoutPrincipalKeepsFullCountsForDemoProfiles() throws Exception {
        when(dashboardQueryService.getDashboard(isNull())).thenReturn(view(Map.of("DRAFT", 3L)));

        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementStatusCounts.DRAFT").value(3));
    }

    private DashboardView view(Map<String, Long> statusCounts) {
        return new DashboardView(statusCounts, 0L, 0L, List.of(), List.of());
    }
}
