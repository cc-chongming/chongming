package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.task.application.TaskFlowGuard;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.protocol.DevTaskStateMachine;
import ai.cc.chongming.task.infrastructure.InMemoryDevTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the manual requirement lifecycle endpoints: while a requirement still owns a
 * non-DONE development task, start-development/complete/cancel must be rejected with 409 and
 * the stable REQUIREMENT_HAS_ACTIVE_TASK code; without an active task the commands proceed.
 *
 * @author wangli
 */
class RequirementCommandControllerTaskGuardTests {

    private RequirementCommandService commandService;
    private InMemoryDevTaskRepository devTaskRepository;
    private MockMvc mockMvc;
    private UUID requirementId;

    @BeforeEach
    void setUp() {
        commandService = mock(RequirementCommandService.class);
        devTaskRepository = new InMemoryDevTaskRepository();
        TaskFlowGuard guard = new TaskFlowGuard(devTaskRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new RequirementCommandController(commandService, null, new ObjectMapper(), guard))
                .setControllerAdvice(new RequirementExceptionHandler())
                .build();
        requirementId = UUID.randomUUID();
    }

    @Test
    void manualCompleteIsRejectedWhileAnActiveDevTaskExists() throws Exception {
        seedTask(DevTaskStatus.PENDING_ASSIGN);

        mockMvc.perform(post("/api/requirements/{requirementId}/complete", requirementId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUIREMENT_HAS_ACTIVE_TASK"));

        verify(commandService, never()).complete(any(), anyLong());
    }

    @Test
    void manualStartDevelopmentAndCancelAreRejectedWhileAnActiveDevTaskExists() throws Exception {
        seedTask(DevTaskStatus.DEVELOPING);

        mockMvc.perform(post("/api/requirements/{requirementId}/start-development", requirementId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUIREMENT_HAS_ACTIVE_TASK"));
        mockMvc.perform(post("/api/requirements/{requirementId}/cancel", requirementId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUIREMENT_HAS_ACTIVE_TASK"));

        verify(commandService, never()).startDevelopment(any(), anyLong());
        verify(commandService, never()).cancel(any(), anyLong());
    }

    @Test
    void manualCompleteProceedsWhenNoDevTaskExists() throws Exception {
        when(commandService.complete(eq(new RequirementId(requirementId)), eq(2L)))
                .thenReturn(restoredRequirement(RequirementStatus.DEVELOPING));

        mockMvc.perform(post("/api/requirements/{requirementId}/complete", requirementId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEVELOPING"));

        verify(commandService).complete(new RequirementId(requirementId), 2L);
    }

    @Test
    void manualCompleteProceedsWhenTheDevTaskIsAlreadyDone() throws Exception {
        seedTask(DevTaskStatus.DONE);
        when(commandService.complete(eq(new RequirementId(requirementId)), eq(2L)))
                .thenReturn(restoredRequirement(RequirementStatus.DONE));

        mockMvc.perform(post("/api/requirements/{requirementId}/complete", requirementId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk());

        verify(commandService).complete(new RequirementId(requirementId), 2L);
    }

    private void seedTask(DevTaskStatus status) {
        DevTask task = DevTask.draft(
                new DevTaskId(UUID.randomUUID()),
                new RequirementId(requirementId),
                null,
                "任务流转验证任务");
        devTaskRepository.save(task);
        if (status == DevTaskStatus.PENDING_ASSIGN) {
            return;
        }
        DevTaskStateMachine stateMachine = new DevTaskStateMachine();
        DevTask loaded = devTaskRepository.findById(task.taskId()).orElseThrow();
        loaded.assign("bob", "admin", stateMachine);
        devTaskRepository.save(loaded);
        if (status == DevTaskStatus.DEVELOPING) {
            return;
        }
        DevTask developing = devTaskRepository.findById(task.taskId()).orElseThrow();
        developing.submitAcceptance("bob", stateMachine);
        devTaskRepository.save(developing);
        DevTask pendingAcceptance = devTaskRepository.findById(task.taskId()).orElseThrow();
        pendingAcceptance.accept("验收通过", stateMachine);
        devTaskRepository.save(pendingAcceptance);
    }

    private Requirement restoredRequirement(RequirementStatus status) {
        return Requirement.restore(
                new RequirementId(requirementId),
                "统一身份同步",
                "同步基础身份",
                "alice",
                "bob",
                "cx-ai",
                "P1",
                status,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                2L);
    }
}
