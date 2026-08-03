package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.application.RequirementQueryService;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [AIREVIEW-PLAN-021#2] HTTP contract tests for requirement creation and list reads.
 *
 * @author zyj
 */
class RequirementControllerTests {

    private RequirementCommandService commandService;
    private RequirementQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        commandService = mock(RequirementCommandService.class);
        queryService = mock(RequirementQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new RequirementCommandController(commandService),
                        new RequirementQueryController(queryService))
                .setControllerAdvice(new RequirementExceptionHandler())
                .build();
    }

    @Test
    void createsRequirementAsDraft() throws Exception {
        Requirement created = Requirement.restore(
                new RequirementId(UUID.randomUUID()),
                "统一身份同步",
                "同步基础身份",
                "alice",
                "bob",
                "cx-ai",
                "P1",
                RequirementStatus.DRAFT,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                0L);
        when(commandService.create(any())).thenReturn(created);

        mockMvc.perform(post("/api/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"统一身份同步","description":"同步基础身份","assigneeId":"bob","repositoryPath":"cx-ai","priority":"P1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.id().value().toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void exposesFilteredRequirementPage() throws Exception {
        UUID requirementId = UUID.randomUUID();
        when(queryService.findPage("DRAFT", "bob", "身份", 1, 20)).thenReturn(new RequirementQueryService.RequirementPage(
                List.of(new RequirementQueryService.RequirementView(
                        requirementId,
                        "统一身份同步",
                        "同步基础身份",
                        "DRAFT",
                        "alice",
                        "bob",
                        "cx-ai",
                        "P1",
                        null,
                        0L,
                        "2026-08-01T00:00:00Z",
                        "2026-08-01T00:00:00Z")),
                1,
                20,
                1));

        mockMvc.perform(get("/api/requirements")
                        .param("status", "DRAFT")
                        .param("assignee", "bob")
                        .param("keyword", "身份"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(requirementId.toString()))
                .andExpect(jsonPath("$.items[0].status").value("DRAFT"));
    }
}
