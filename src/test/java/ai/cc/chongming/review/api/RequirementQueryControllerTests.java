package ai.cc.chongming.review.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import ai.cc.chongming.review.application.RequirementQueryService;
import ai.cc.chongming.review.application.RequirementQueryService.RequirementPage;
import ai.cc.chongming.review.application.RequirementQueryService.RequirementView;
import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementVisibility;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-027] HTTP contract tests for viewer-scoped requirement reads: administrators
 * and demo requests without a principal keep the platform-wide view, every other role is scoped
 * to its own creations plus dev-task assignments, and hidden details surface 404 without
 * leaking existence.
 *
 * @author wangli
 */
class RequirementQueryControllerTests {

    private RequirementQueryService queryService;
    private DevTaskRepository devTaskRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(RequirementQueryService.class);
        devTaskRepository = mock(DevTaskRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RequirementQueryController(queryService, devTaskRepository))
                .setControllerAdvice(new RequirementExceptionHandler())
                .build();
    }

    @Test
    void administratorListKeepsThePlatformWideView() throws Exception {
        when(queryService.findPage(isNull(), isNull(), isNull(), eq(1), eq(20), isNull()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/requirements")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("admin", "管理员", "ADMIN")))
                .andExpect(status().isOk());

        verify(devTaskRepository, never()).findRequirementIdsByAssignee(any());
    }

    @Test
    void developerListIsScopedToOwnCreationsPlusAssignedRequirements() throws Exception {
        RequirementId assignedId = new RequirementId(UUID.randomUUID());
        when(devTaskRepository.findRequirementIdsByAssignee("dev-zhang")).thenReturn(Set.of(assignedId));
        when(queryService.findPage(isNull(), isNull(), isNull(), eq(1), eq(20), any(RequirementVisibility.class)))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/requirements")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER")))
                .andExpect(status().isOk());

        ArgumentCaptor<RequirementVisibility> visibility = ArgumentCaptor.forClass(RequirementVisibility.class);
        verify(queryService).findPage(isNull(), isNull(), isNull(), eq(1), eq(20), visibility.capture());
        assertThat(visibility.getValue().viewerUsername()).isEqualTo("dev-zhang");
        assertThat(visibility.getValue().assignedRequirementIds()).containsExactly(assignedId);
    }

    @Test
    void legacyUserRoleIsTreatedAsDeveloperScope() throws Exception {
        when(devTaskRepository.findRequirementIdsByAssignee("legacy-li")).thenReturn(Set.of());
        when(queryService.findPage(isNull(), isNull(), isNull(), eq(1), eq(20), any(RequirementVisibility.class)))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/requirements")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("legacy-li", "李遗留", "USER")))
                .andExpect(status().isOk());

        ArgumentCaptor<RequirementVisibility> visibility = ArgumentCaptor.forClass(RequirementVisibility.class);
        verify(queryService).findPage(isNull(), isNull(), isNull(), eq(1), eq(20), visibility.capture());
        assertThat(visibility.getValue().viewerUsername()).isEqualTo("legacy-li");
        assertThat(visibility.getValue().assignedRequirementIds()).isEmpty();
    }

    @Test
    void listWithoutPrincipalKeepsFullVisibilityForDemoProfiles() throws Exception {
        when(queryService.findPage(isNull(), isNull(), isNull(), eq(1), eq(20), isNull()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/requirements"))
                .andExpect(status().isOk());

        verify(devTaskRepository, never()).findRequirementIdsByAssignee(any());
    }

    @Test
    void hiddenDetailSurfacesNotFoundWithoutLeakingExistence() throws Exception {
        UUID requirementId = UUID.randomUUID();
        when(devTaskRepository.findRequirementIdsByAssignee("dev-zhang")).thenReturn(Set.of());
        when(queryService.findById(eq(new RequirementId(requirementId)), any(RequirementVisibility.class)))
                .thenThrow(new RequirementDomainException(RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement was not found"));

        mockMvc.perform(get("/api/requirements/{requirementId}", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUIREMENT_NOT_FOUND"));
    }

    @Test
    void administratorDetailBypassesTheVisibilityScope() throws Exception {
        UUID requirementId = UUID.randomUUID();
        when(queryService.findById(eq(new RequirementId(requirementId)), isNull()))
                .thenReturn(view(requirementId, "admin"));

        mockMvc.perform(get("/api/requirements/{requirementId}", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("admin", "管理员", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requirementId.toString()))
                .andExpect(jsonPath("$.creatorId").value("admin"));
    }

    /**
     * [AIREVIEW-PLAN-111] Administrators fetch the uploaded requirement document through the
     * scoped document endpoint with the filename and Markdown body intact.
     */
    @Test
    void documentEndpointServesTheUploadedMarkdown() throws Exception {
        UUID requirementId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        RequirementQueryService.RequirementDocumentView document =
                new RequirementQueryService.RequirementDocumentView(reviewId, 1, "requirement.md", "# 需求\n\n正文");
        when(queryService.findDocument(eq(new RequirementId(requirementId)), isNull()))
                .thenReturn(document);

        mockMvc.perform(get("/api/requirements/{requirementId}/document", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("admin", "管理员", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.filename").value("requirement.md"))
                .andExpect(jsonPath("$.markdown").value("# 需求\n\n正文"));
    }

    /**
     * [AIREVIEW-PLAN-111] Hidden or missing requirement documents surface the same 404 contract
     * as the detail read without leaking existence.
     */
    @Test
    void hiddenDocumentSurfacesNotFoundWithoutLeakingExistence() throws Exception {
        UUID requirementId = UUID.randomUUID();
        when(devTaskRepository.findRequirementIdsByAssignee("dev-zhang")).thenReturn(Set.of());
        when(queryService.findDocument(eq(new RequirementId(requirementId)), any(RequirementVisibility.class)))
                .thenThrow(new RequirementDomainException(
                        RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement document was not found"));

        mockMvc.perform(get("/api/requirements/{requirementId}/document", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUIREMENT_NOT_FOUND"));
    }

    private RequirementPage emptyPage() {
        return new RequirementPage(List.of(), 1, 20, 0);
    }

    private RequirementView view(UUID requirementId, String creatorId) {
        return new RequirementView(
                requirementId, "统一身份同步", "同步基础身份", "DRAFT", creatorId, null, "cx-ai", "P1", null, 0L,
                "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");
    }
}
