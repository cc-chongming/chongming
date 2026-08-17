package ai.cc.chongming.review.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.auth.api.AuthJwtFilter;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.application.RequirementCommandService.CreateRequirementCommand;
import ai.cc.chongming.review.application.RequirementQueryService;
import ai.cc.chongming.review.application.RequirementQueryService.RequirementView;
import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * [AIREVIEW-PLAN-027] Role gate for requirement creation and the creator/administrator
 * ownership gate for revise/delete. Requests without a principal keep the historical open
 * demo behaviour on every endpoint.
 *
 * @author wangli
 */
class RequirementCommandControllerTests {

    private static final String CREATE_BODY = """
            {"title":"统一身份同步","description":"同步基础身份","assigneeId":"bob","repositoryPath":"cx-ai","priority":"P1"}
            """;

    private RequirementCommandService commandService;
    private RequirementQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        commandService = mock(RequirementCommandService.class);
        queryService = mock(RequirementQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RequirementCommandController(
                        commandService, null, new ObjectMapper(), null, queryService))
                .setControllerAdvice(new RequirementExceptionHandler())
                .build();
    }

    @Test
    void productManagerCreatesRequirementAsItsOwnCreator() throws Exception {
        when(commandService.create(any())).thenReturn(createdDraft());

        mockMvc.perform(post("/api/requirements")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("pm-wang", "王产品", "PRODUCT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        assertThat(capturedCreator()).isEqualTo("pm-wang");
    }

    @Test
    void projectManagerCreatesRequirementAsItsOwnCreator() throws Exception {
        when(commandService.create(any())).thenReturn(createdDraft());

        mockMvc.perform(post("/api/requirements")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("pj-zhao", "赵项目", "PROJECT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        assertThat(capturedCreator()).isEqualTo("pj-zhao");
    }

    @Test
    void developerCreateIsRejectedWithForbidden() throws Exception {
        mockMvc.perform(post("/api/requirements")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(commandService, never()).create(any());
    }

    @Test
    void legacyUserRoleCreateIsRejectedWithForbidden() throws Exception {
        mockMvc.perform(post("/api/requirements")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("legacy-li", "李遗留", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(commandService, never()).create(any());
    }

    @Test
    void createWithoutPrincipalKeepsTheIdentityProviderFallback() throws Exception {
        when(commandService.create(any())).thenReturn(createdDraft());

        mockMvc.perform(post("/api/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        assertThat(capturedCreator()).isNull();
    }

    @Test
    void creatorMayReviseItsOwnRequirement() throws Exception {
        UUID requirementId = UUID.randomUUID();
        stubOwnership(requirementId, "dev-zhang");
        when(commandService.revise(any(), any())).thenReturn(createdDraft());

        mockMvc.perform(put("/api/requirements/{requirementId}", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"修订标题","expectedVersion":1}
                                """))
                .andExpect(status().isOk());

        verify(commandService).revise(eq(new RequirementId(requirementId)), any());
    }

    @Test
    void nonCreatorReviseIsRejectedWithForbidden() throws Exception {
        UUID requirementId = UUID.randomUUID();
        stubOwnership(requirementId, "pm-wang");

        mockMvc.perform(put("/api/requirements/{requirementId}", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"修订标题","expectedVersion":1}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(commandService, never()).revise(any(), any());
    }

    @Test
    void administratorMayReviseAnyRequirement() throws Exception {
        UUID requirementId = UUID.randomUUID();
        when(commandService.revise(any(), any())).thenReturn(createdDraft());

        mockMvc.perform(put("/api/requirements/{requirementId}", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("admin", "管理员", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"修订标题","expectedVersion":1}
                                """))
                .andExpect(status().isOk());

        // Administrators bypass the ownership lookup entirely.
        verify(queryService, never()).findById(any(), any());
        verify(queryService, never()).findById(any(RequirementId.class));
    }

    @Test
    void reviseOnHiddenRequirementSurfacesNotFound() throws Exception {
        UUID requirementId = UUID.randomUUID();
        when(queryService.findById(new RequirementId(requirementId)))
                .thenThrow(new RequirementDomainException(RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement was not found"));

        mockMvc.perform(put("/api/requirements/{requirementId}", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"修订标题","expectedVersion":1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUIREMENT_NOT_FOUND"));
    }

    @Test
    void nonCreatorDeleteIsRejectedWithForbidden() throws Exception {
        UUID requirementId = UUID.randomUUID();
        stubOwnership(requirementId, "pm-wang");

        mockMvc.perform(delete("/api/requirements/{requirementId}", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER"))
                        .param("expectedVersion", "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(commandService, never()).delete(any(), any(Long.class));
    }

    @Test
    void creatorDeletePassesTheOwnershipGate() throws Exception {
        UUID requirementId = UUID.randomUUID();
        stubOwnership(requirementId, "dev-zhang");

        mockMvc.perform(delete("/api/requirements/{requirementId}", requirementId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("dev-zhang", "张开发", "DEVELOPER"))
                        .param("expectedVersion", "2"))
                .andExpect(status().isNoContent());

        verify(commandService).delete(new RequirementId(requirementId), 2L);
    }

    private void stubOwnership(UUID requirementId, String creatorId) {
        when(queryService.findById(new RequirementId(requirementId)))
                .thenReturn(new RequirementView(
                        requirementId, "统一身份同步", "同步基础身份", "DRAFT", creatorId, null, "cx-ai", "P1", null, 1L,
                        "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z"));
    }

    private String capturedCreator() {
        ArgumentCaptor<CreateRequirementCommand> command = ArgumentCaptor.forClass(CreateRequirementCommand.class);
        verify(commandService).create(command.capture());
        return command.getValue().creatorUsername();
    }

    private Requirement createdDraft() {
        return Requirement.restore(
                new RequirementId(UUID.randomUUID()),
                "统一身份同步",
                "同步基础身份",
                "pm-wang",
                "bob",
                "cx-ai",
                "P1",
                RequirementStatus.DRAFT,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                0L);
    }
}
