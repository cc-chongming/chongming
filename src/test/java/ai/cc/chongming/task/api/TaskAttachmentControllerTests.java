package ai.cc.chongming.task.api;

import ai.cc.chongming.auth.api.AuthJwtFilter;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.task.application.DevTaskAttachmentService;
import ai.cc.chongming.task.application.DevTaskQueryService;
import ai.cc.chongming.task.application.DevTaskQueryService.DevTaskView;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.infrastructure.InMemoryTaskAttachmentStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [AIREVIEW-PLAN-031#3] HTTP contract for task delivery attachments: holder/admin upload gate,
 * size ceiling, participant download with attachment headers and uploader/admin delete.
 *
 * @author wangli
 */
class TaskAttachmentControllerTests {

    private static final AuthPrincipal ADMIN = new AuthPrincipal("admin", "管理员", "ADMIN");
    private static final AuthPrincipal HOLDER = new AuthPrincipal("bob", "Bob", "DEVELOPER");
    private static final AuthPrincipal OTHER = new AuthPrincipal("carol", "Carol", "DEVELOPER");

    private DevTaskQueryService queryService;
    private MockMvc mockMvc;
    private UUID taskId;

    @BeforeEach
    void setUp() {
        queryService = mock(DevTaskQueryService.class);
        taskId = UUID.randomUUID();
        when(queryService.findById(new DevTaskId(taskId))).thenReturn(new DevTaskView(
                taskId, UUID.randomUUID(), "统一身份同步", null, "统一身份同步", "DEVELOPING",
                "bob", "Bob", "admin", null, null, List.of(), 1L,
                "2026-08-20T00:00:00Z", "2026-08-20T00:00:00Z"));
        DevTaskAttachmentService service = new DevTaskAttachmentService(
                new InMemoryTaskAttachmentStore(), queryService);
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskAttachmentController(service))
                .setControllerAdvice(new DevTaskExceptionHandler())
                .build();
    }

    @Test
    void holderUploadsThenEveryParticipantListsAndDownloads() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/tasks/{taskId}/attachments", taskId)
                        .file(new MockMultipartFile("file", "design.md", "text/markdown", "# 设计".getBytes()))
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, HOLDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("design.md"))
                .andExpect(jsonPath("$.uploadedBy").value("bob"))
                .andReturn();
        String attachmentId = com.jayway.jsonpath.JsonPath.read(
                uploaded.getResponse().getContentAsString(), "$.attachmentId");

        mockMvc.perform(get("/api/tasks/{taskId}/attachments", taskId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, OTHER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("design.md"));

        mockMvc.perform(get("/api/tasks/{taskId}/attachments/{attachmentId}", taskId, attachmentId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, OTHER))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''design.md")))
                .andExpect(content().bytes("# 设计".getBytes()));
    }

    @Test
    void nonHolderUploadIsRejectedWith403() throws Exception {
        mockMvc.perform(multipart("/api/tasks/{taskId}/attachments", taskId)
                        .file(new MockMultipartFile("file", "design.md", "text/markdown", "x".getBytes()))
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, OTHER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void oversizedUploadIsRejectedWith413() throws Exception {
        byte[] oversized = new byte[(int) DevTaskAttachmentService.MAX_ATTACHMENT_BYTES + 1];
        mockMvc.perform(multipart("/api/tasks/{taskId}/attachments", taskId)
                        .file(new MockMultipartFile("file", "big.bin", "application/octet-stream", oversized))
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, HOLDER))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_TOO_LARGE"));
    }

    @Test
    void deleteIsLimitedToUploaderOrAdmin() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/tasks/{taskId}/attachments", taskId)
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "n".getBytes()))
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, HOLDER))
                .andExpect(status().isOk())
                .andReturn();
        String attachmentId = com.jayway.jsonpath.JsonPath.read(
                uploaded.getResponse().getContentAsString(), "$.attachmentId");

        mockMvc.perform(delete("/api/tasks/{taskId}/attachments/{attachmentId}", taskId, attachmentId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, OTHER))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/tasks/{taskId}/attachments/{attachmentId}", taskId, attachmentId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, ADMIN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/tasks/{taskId}/attachments", taskId)
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, HOLDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownAttachmentDownloadReturns404() throws Exception {
        mockMvc.perform(get("/api/tasks/{taskId}/attachments/{attachmentId}", taskId, UUID.randomUUID())
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, HOLDER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }
}
