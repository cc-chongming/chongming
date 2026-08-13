package ai.cc.chongming.task.api;

import ai.cc.chongming.auth.api.AuthJwtFilter;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.task.application.DevTaskCommandService;
import ai.cc.chongming.task.application.DevTaskProvisioningListener;
import ai.cc.chongming.task.application.DevTaskQueryService;
import ai.cc.chongming.task.application.DevTaskQueryService.DevTaskView;
import ai.cc.chongming.task.application.DevTaskQueryService.TaskPage;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract tests for the task dispatch endpoints, covering the role gates read from the
 * principal attribute, Bean Validation on command bodies and the stable ProblemDetail error
 * mapping including the catch-all 500 handler.
 *
 * @author wangli
 */
class DevTaskControllerTests {

    private static final AuthPrincipal ADMIN = new AuthPrincipal("admin", "管理员", "ADMIN");
    private static final AuthPrincipal USER = new AuthPrincipal("bob", "Bob", "USER");

    private DevTaskCommandService commandService;
    private DevTaskQueryService queryService;
    private DevTaskProvisioningListener provisioningListener;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        commandService = mock(DevTaskCommandService.class);
        queryService = mock(DevTaskQueryService.class);
        provisioningListener = mock(DevTaskProvisioningListener.class);
        // Standalone MockMvc skips the application context, so @Valid only kicks in with an
        // explicitly configured validator.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DevTaskController(commandService, queryService, provisioningListener))
                .setControllerAdvice(new DevTaskExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void listExposesTaskPageWithJoinedRequirementTitle() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        when(queryService.findPage("PENDING_ASSIGN", null, null, null, 1, 20)).thenReturn(new TaskPage(
                List.of(new DevTaskView(
                        taskId, requirementId, "统一身份同步", null, "统一身份同步", "PENDING_ASSIGN",
                        null, null, null, null, 0L, "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z")),
                1, 20, 1L));

        mockMvc.perform(get("/api/tasks")
                        .param("status", "PENDING_ASSIGN")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.items[0].requirementTitle").value("统一身份同步"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING_ASSIGN"));
    }

    @Test
    void listPassesRequirementIdFilterToTheQueryService() throws Exception {
        UUID requirementId = UUID.randomUUID();
        when(queryService.findPage(null, null, null, requirementId.toString(), 1, 20))
                .thenReturn(new TaskPage(List.of(), 1, 20, 0L));

        mockMvc.perform(get("/api/tasks")
                        .param("requirementId", requirementId.toString())
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, USER))
                .andExpect(status().isOk());

        verify(queryService).findPage(null, null, null, requirementId.toString(), 1, 20);
    }

    @Test
    void mineFilterUsesTheCallerUsername() throws Exception {
        when(queryService.findPage(null, "bob", null, null, 1, 20))
                .thenReturn(new TaskPage(List.of(), 1, 20, 0L));

        mockMvc.perform(get("/api/tasks")
                        .param("mine", "true")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, USER))
                .andExpect(status().isOk());

        verify(queryService).findPage(null, "bob", null, null, 1, 20);
    }

    @Test
    void getReturnsTaskViewWithAssigneeDisplayName() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(queryService.findById(new DevTaskId(taskId))).thenReturn(new DevTaskView(
                taskId, UUID.randomUUID(), "统一身份同步", null, "统一身份同步", "DEVELOPING",
                "bob", "李开发", "admin", null, 2L, "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"));

        mockMvc.perform(get("/api/tasks/{taskId}", taskId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.assigneeUsername").value("bob"))
                .andExpect(jsonPath("$.assigneeDisplayName").value("李开发"));
    }

    @Test
    void getWithoutPrincipalIsForbidden() throws Exception {
        mockMvc.perform(get("/api/tasks/{taskId}", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void assignByAdminReturnsUpdatedTaskView() throws Exception {
        UUID taskId = UUID.randomUUID();
        DevTaskView assigned = new DevTaskView(
                taskId, UUID.randomUUID(), "统一身份同步", null, "统一身份同步", "DEVELOPING",
                "bob", "李开发", "admin", null, 1L, "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z");
        when(queryService.findById(new DevTaskId(taskId))).thenReturn(assigned);

        mockMvc.perform(post("/api/tasks/{taskId}/assign", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeUsername\":\"bob\",\"expectedVersion\":0}")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEVELOPING"))
                .andExpect(jsonPath("$.dispatcherUsername").value("admin"));

        verify(commandService).assign(eq(new DevTaskId(taskId)), eq("bob"), eq("admin"), eq(0L));
    }

    @Test
    void assignByRegularUserIsForbiddenWithStableCode() throws Exception {
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/api/tasks/{taskId}/assign", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeUsername\":\"bob\",\"expectedVersion\":0}")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void assignWithOverlongAssigneeUsernameIsRejectedByBeanValidation() throws Exception {
        UUID taskId = UUID.randomUUID();
        String overlongAssignee = "a".repeat(65);

        mockMvc.perform(post("/api/tasks/{taskId}/assign", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeUsername\":\"" + overlongAssignee + "\",\"expectedVersion\":0}")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TASK_REQUEST"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void submitAcceptanceForwardsCallerAsOperator() throws Exception {
        UUID taskId = UUID.randomUUID();
        DevTaskView submitted = new DevTaskView(
                taskId, UUID.randomUUID(), "统一身份同步", null, "统一身份同步", "PENDING_ACCEPTANCE",
                "bob", "李开发", "admin", null, 2L, "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z");
        when(queryService.findById(new DevTaskId(taskId))).thenReturn(submitted);

        mockMvc.perform(post("/api/tasks/{taskId}/submit-acceptance", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_ACCEPTANCE"));

        verify(commandService).submitAcceptance(eq(new DevTaskId(taskId)), eq("bob"), eq(1L));
    }

    @Test
    void versionConflictSurfacesConflictWithStableCode() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(commandService.assign(any(), any(), any(), anyLong()))
                .thenThrow(new TaskDomainException(TaskErrorCode.VERSION_CONFLICT, "stale version"));

        mockMvc.perform(post("/api/tasks/{taskId}/assign", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeUsername\":\"bob\",\"expectedVersion\":7}")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void requirementStateConflictSurfacesConflictWithStableCode() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(commandService.assign(any(), any(), any(), anyLong()))
                .thenThrow(new TaskDomainException(
                        TaskErrorCode.TASK_REQUIREMENT_STATE_CONFLICT, "requirement not approved"));

        mockMvc.perform(post("/api/tasks/{taskId}/assign", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeUsername\":\"bob\",\"expectedVersion\":0}")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_REQUIREMENT_STATE_CONFLICT"));
    }

    @Test
    void unknownTaskSurfacesNotFoundWithStableCode() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(queryService.findById(new DevTaskId(taskId)))
                .thenThrow(new TaskDomainException(TaskErrorCode.TASK_NOT_FOUND, "dev task was not found"));

        mockMvc.perform(get("/api/tasks/{taskId}", taskId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void unexpectedFailureSurfacesStableServerErrorWithTraceId() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(commandService.assign(any(), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("storage outage"));

        mockMvc.perform(post("/api/tasks/{taskId}/assign", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeUsername\":\"bob\",\"expectedVersion\":0}")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("TASK_UNEXPECTED_FAILURE"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void reconcileByAdminReportsCreatedCount() throws Exception {
        when(provisioningListener.reconcile()).thenReturn(3L);

        mockMvc.perform(post("/api/tasks/reconcile")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3));
    }

    @Test
    void reconcileByRegularUserIsForbidden() throws Exception {
        mockMvc.perform(post("/api/tasks/reconcile")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void requestsWithoutPrincipalAreForbidden() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
