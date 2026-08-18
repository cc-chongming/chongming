package ai.cc.chongming.review.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.review.application.ReviewIntakeException;
import ai.cc.chongming.review.application.ReviewIntakeResult;
import ai.cc.chongming.review.application.ReviewIntakeService;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.RequirementDocument;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.document.StoredRequirementSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Tests the multipart review intake HTTP contract before persistence is enabled.
 *
 * @author wangli
 */
class ReviewCommandControllerTests {

    private ReviewIntakeService intakeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        intakeService = mock(ReviewIntakeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReviewCommandController(intakeService))
                .setControllerAdvice(new ReviewIntakeExceptionHandler())
                .build();
    }

    @Test
    void acceptsMultipartMarkdownAndReturnsSnapshotIdentity() throws Exception {
        UUID reviewUuid = UUID.randomUUID();
        when(intakeService.intake(any())).thenReturn(new ReviewIntakeResult(
                new RequirementSnapshot(
                        UUID.randomUUID(),
                        new ReviewId(reviewUuid),
                        1,
                        "user-001",
                        "D:/repository",
                        "main",
                        "abc123",
                        "requirements.md",
                        "a".repeat(64),
                        "b".repeat(64),
                        "markdown-line-parser-v1",
                        new RequirementDocument(List.of(), List.of(), 0, 0, false),
                        Instant.now()),
                new StoredRequirementSnapshot(
                        Path.of("raw.md"), Path.of("normalized.md"), Path.of("snapshot-manifest.json")),
                false));

        mockMvc.perform(multipart("/api/reviews")
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirements.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "D:/repository")
                        .param("branch", "main")
                        .param("commit", "abc123")
                        .param("submitter", "user-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reviewId").value(reviewUuid.toString()))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.snapshotHash").value("b".repeat(64)))
                .andExpect(jsonPath("$.statusUrl").value("/api/reviews/" + reviewUuid));
    }

    @Test
    void returnsUnprocessableProblemForRejectedMarkdown() throws Exception {
        when(intakeService.intake(any())).thenThrow(
                ReviewIntakeException.invalid("UNSUPPORTED_FILE_TYPE", "Only .md files are supported"));

        mockMvc.perform(multipart("/api/reviews")
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirements.pdf",
                                "application/pdf",
                                "not a PDF".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "D:/repository")
                        .param("submitter", "user-001"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void returnsStableProblemWhenRequiredFilePartIsMissing() throws Exception {
        // [AIREVIEW-PLAN-025] Neither an uploaded file nor typed text now yields a stable 400.
        mockMvc.perform(multipart("/api/reviews")
                        .param("repositoryPath", "D:/repository")
                        .param("submitter", "user-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUIREMENT_DOCUMENT"));
    }

    /**
     * [AIREVIEW-PLAN-025] Typed Markdown is accepted in place of an uploaded file and reaches the
     * intake service as a uniform document.
     */
    @Test
    void acceptsTypedMarkdownInsteadOfAnUploadedFile() throws Exception {
        UUID reviewUuid = UUID.randomUUID();
        when(intakeService.intake(any())).thenReturn(new ReviewIntakeResult(
                new RequirementSnapshot(
                        UUID.randomUUID(),
                        new ReviewId(reviewUuid),
                        1,
                        "user-001",
                        "D:/repository",
                        "main",
                        "abc123",
                        "requirement.md",
                        "a".repeat(64),
                        "b".repeat(64),
                        "markdown-line-parser-v1",
                        new RequirementDocument(List.of(), List.of(), 0, 0, false),
                        Instant.now()),
                new StoredRequirementSnapshot(
                        Path.of("raw.md"), Path.of("normalized.md"), Path.of("snapshot-manifest.json")),
                false));

        mockMvc.perform(multipart("/api/reviews")
                        .param("requirementText", "# Requirement\nTyped intake body.")
                        .param("repositoryPath", "D:/repository")
                        .param("submitter", "user-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reviewId").value(reviewUuid.toString()));
    }

    /**
     * [AIREVIEW-PLAN-025] Supplying both transports at once is rejected with a stable 400.
     */
    @Test
    void rejectsProvidingBothFileAndTypedMarkdown() throws Exception {
        mockMvc.perform(multipart("/api/reviews")
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirements.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("requirementText", "# Requirement")
                        .param("repositoryPath", "D:/repository")
                        .param("submitter", "user-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INTAKE_DOCUMENT"));
    }

    @Test
    void returnsStableProblemWhenRequiredParameterIsMissing() throws Exception {
        mockMvc.perform(multipart("/api/reviews")
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirements.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "D:/repository"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"));
    }

    @Test
    void returnsConflictProblemWhenIntakeIsCancelled() throws Exception {
        when(intakeService.intake(any())).thenThrow(new ReviewIntakeException(
                "INTAKE_CANCELLED", HttpStatus.CONFLICT, "Requirement intake was cancelled"));

        mockMvc.perform(multipart("/api/reviews")
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirements.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "D:/repository")
                        .param("submitter", "user-001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTAKE_CANCELLED"));
    }

    @Test
    void returnsPayloadTooLargeProblemWhenMultipartLimitIsExceeded() throws Exception {
        when(intakeService.intake(any())).thenThrow(new MaxUploadSizeExceededException(64));

        mockMvc.perform(multipart("/api/reviews")
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirements.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "D:/repository")
                        .param("submitter", "user-001"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }
    @Test
    void returnsSafeProblemForUnexpectedIntakeFailure() throws Exception {
        when(intakeService.intake(any())).thenThrow(new IllegalStateException("workspace unavailable"));

        mockMvc.perform(multipart("/api/reviews")
                        .file(new MockMultipartFile(
                                "requirementFile",
                                "requirements.md",
                                "text/markdown",
                                "# Requirement".getBytes(StandardCharsets.UTF_8)))
                        .param("repositoryPath", "D:/repository")
                        .param("submitter", "user-001"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTAKE_UNEXPECTED_FAILURE"))
                .andExpect(jsonPath("$.detail").value("Unexpected failure while accepting review intake"));
    }
}
