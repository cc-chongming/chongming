package ai.cc.chongming.review.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.application.ReviewIntakeException;
import ai.cc.chongming.review.application.RequirementReviewLaunchException;
import ai.cc.chongming.review.application.RequirementReviewLaunchService;
import ai.cc.chongming.review.application.RequirementReviewLaunchService.LaunchResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * [AIREVIEW-PLAN-023#3] Verifies the multipart draft-launch HTTP contract.
 *
 * @author zyj
 */
class RequirementReviewLaunchControllerTests {

    private final UUID requirementId = UUID.randomUUID();
    private RequirementReviewLaunchService launchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        launchService = mock(RequirementReviewLaunchService.class);
        RequirementCommandController controller = new RequirementCommandController(
                mock(RequirementCommandService.class), launchService, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RequirementExceptionHandler(), new RequirementReviewLaunchExceptionHandler())
                .build();
    }

    @Test
    void acceptsOneMultipartCommandAndReturnsTheLiveDestination() throws Exception {
        UUID reviewId = UUID.randomUUID();
        when(launchService.launch(eq(new ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId(requirementId)), any()))
                .thenReturn(new LaunchResult(
                        requirementId,
                        reviewId,
                        1,
                        1L,
                        3L,
                        "PENDING_REVIEW",
                        "PLANNING",
                        "STARTED",
                        false,
                        false,
                        "/reviews/" + reviewId + "/live"));

        mockMvc.perform(multipart("/api/requirements/{requirementId}/reviews", requirementId)
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirement.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "cx-ai")
                        .param("branch", "main")
                        .param("submitter", "product-owner")
                        .param("publicTasks", "[\"Review product scope\",\"Review backend\"]")
                        .param("changeReason", "Initial review")
                        .param("initialMessage", "Begin review")
                        .param("expectedVersion", "0")
                        .header("Idempotency-Key", "launch-001")
                        .header("X-Trace-Id", "trace-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.stage").value("PLANNING"))
                .andExpect(jsonPath("$.phase").value("STARTED"))
                .andExpect(jsonPath("$.recoverable").value(false))
                .andExpect(jsonPath("$.liveUrl").value("/reviews/" + reviewId + "/live"));

        ArgumentCaptor<RequirementReviewLaunchService.LaunchCommand> command =
                ArgumentCaptor.forClass(RequirementReviewLaunchService.LaunchCommand.class);
        org.mockito.Mockito.verify(launchService).launch(
                eq(new ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId(requirementId)), command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().publicTasks())
                .containsExactly("Review product scope", "Review backend");
        org.assertj.core.api.Assertions.assertThat(command.getValue().idempotencyKey()).isEqualTo("launch-001");
    }

    @Test
    void rejectsMalformedPublicTaskJsonWithAStableCode() throws Exception {
        mockMvc.perform(multipart("/api/requirements/{requirementId}/reviews", requirementId)
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirement.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "cx-ai")
                        .param("submitter", "product-owner")
                        .param("publicTasks", "not-json")
                        .param("changeReason", "Initial review")
                        .param("initialMessage", "Begin review")
                        .param("expectedVersion", "0")
                        .header("Idempotency-Key", "launch-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PUBLIC_TASKS"));
    }

    @Test
    void exposesTheExistingReviewIdForBindingConflicts() throws Exception {
        UUID existingReviewId = UUID.randomUUID();
        when(launchService.launch(any(), any())).thenThrow(new RequirementReviewLaunchException(
                "REVIEW_ALREADY_BOUND",
                HttpStatus.CONFLICT,
                "requirement is already bound to another review",
                "INTAKE",
                false,
                existingReviewId));

        mockMvc.perform(multipart("/api/requirements/{requirementId}/reviews", requirementId)
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirement.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "cx-ai")
                        .param("submitter", "product-owner")
                        .param("publicTasks", "[\"Review\"]")
                        .param("changeReason", "Initial review")
                        .param("initialMessage", "Begin review")
                        .param("expectedVersion", "0")
                        .header("Idempotency-Key", "launch-001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_BOUND"))
                .andExpect(jsonPath("$.phase").value("INTAKE"))
                .andExpect(jsonPath("$.recoverable").value(false))
                .andExpect(jsonPath("$.existingReviewId").value(existingReviewId.toString()));
    }

    @Test
    void rejectsAnIdempotencyKeyThatCannotFitThePersistentContract() throws Exception {
        mockMvc.perform(multipart("/api/requirements/{requirementId}/reviews", requirementId)
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirement.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "cx-ai")
                        .param("submitter", "product-owner")
                        .param("publicTasks", "[\"Review\"]")
                        .param("changeReason", "Initial review")
                        .param("initialMessage", "Begin review")
                        .param("expectedVersion", "0")
                        .header("Idempotency-Key", "a".repeat(129)))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(launchService);
    }

    @Test
    void preservesTheStableIntakeValidationProblemContract() throws Exception {
        // The draft launch endpoint must expose the same validation contract as standalone intake.
        when(launchService.launch(any(), any())).thenThrow(
                ReviewIntakeException.invalid("EMPTY_DOCUMENT", "Requirement document must not be empty"));

        mockMvc.perform(multipart("/api/requirements/{requirementId}/reviews", requirementId)
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirement.md",
                                "text/markdown",
                                "".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "cx-ai")
                        .param("submitter", "product-owner")
                        .param("publicTasks", "[\"Review\"]")
                        .param("changeReason", "Initial review")
                        .param("initialMessage", "Begin review")
                        .param("expectedVersion", "0")
                        .header("Idempotency-Key", "launch-001"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EMPTY_DOCUMENT"))
                .andExpect(jsonPath("$.detail").value("Requirement document must not be empty"));
    }
}
