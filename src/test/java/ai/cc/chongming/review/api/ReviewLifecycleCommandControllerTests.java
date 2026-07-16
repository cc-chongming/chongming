package ai.cc.chongming.review.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.review.application.ReviewCommandService;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

/**
 * [AIREVIEW-PLAN-010#1.6,#1.7] Covers the public lifecycle command request, response and problem contracts.
 *
 * @author wangli
 */
class ReviewLifecycleCommandControllerTests {

    private final UUID reviewId = UUID.randomUUID();
    private ReviewCommandService commandService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        commandService = mock(ReviewCommandService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReviewLifecycleCommandController(commandService))
                .setControllerAdvice(new ReviewLifecycleCommandExceptionHandler())
                .build();
    }

    @Test
    void acceptsAsynchronousStartWithAnIdempotencyKey() throws Exception {
        when(commandService.start(any(), any())).thenReturn(
                new ReviewCommandService.StartReviewResult(reviewId, 1, 3L, "PLANNING", false));

        mockMvc.perform(post("/api/reviews/{reviewId}/start", reviewId)
                        .header("Idempotency-Key", "start-001")
                        .header("X-Trace-Id", "trace-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"userId":"user-001","publicTasks":["Review requirements"],
                                "changeReason":"Initial plan","initialMessage":"Begin review"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.stage").value("PLANNING"));
    }

    @Test
    void cancelsAfterTheRuntimeSafePointCompletes() throws Exception {
        when(commandService.cancel(any(), eq(3L))).thenReturn(
                Mono.just(new ReviewCommandService.CancelReviewResult(reviewId, 1, 5L, false)));

        MvcResult result = mockMvc.perform(post("/api/reviews/{reviewId}/cancel", reviewId)
                        .param("expectedVersion", "3"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void returnsAcceptedFreshAttemptForRetry() throws Exception {
        when(commandService.retry(any(), eq(5L))).thenReturn(
                new ReviewCommandService.RetryReviewResult(reviewId, 1, 2, 6L, false));

        mockMvc.perform(post("/api/reviews/{reviewId}/retry", reviewId)
                        .param("expectedVersion", "5"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.previousAttempt").value(1))
                .andExpect(jsonPath("$.attemptNo").value(2));
    }

    @Test
    void mapsVersionConflictToStableProblemCode() throws Exception {
        when(commandService.retry(any(), eq(0L))).thenThrow(
                new ReviewDomainException(ReviewErrorCode.VERSION_CONFLICT, "expectedVersion does not match aggregate version"));

        mockMvc.perform(post("/api/reviews/{reviewId}/retry", reviewId)
                        .param("expectedVersion", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }
}
