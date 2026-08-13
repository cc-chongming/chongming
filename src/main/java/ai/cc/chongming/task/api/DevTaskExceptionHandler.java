package ai.cc.chongming.task.api;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Returns stable client errors for development-task endpoints, mirroring the auth domain's
 * ProblemDetail contract: a {@code code} property plus an {@code x-trace-id} correlation
 * value (also exposed as a response header).
 *
 * @author wangli
 */
@RestControllerAdvice(assignableTypes = DevTaskController.class)
public class DevTaskExceptionHandler {

    /** Correlation header echoed on every task error response. */
    public static final String TRACE_HEADER = "x-trace-id";

    @ExceptionHandler(TaskDomainException.class)
    public ResponseEntity<ProblemDetail> taskDomainFailure(TaskDomainException exception, HttpServletResponse response) {
        HttpStatus status = switch (exception.errorCode()) {
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case VERSION_CONFLICT, ILLEGAL_TASK_TRANSITION, TASK_REQUIREMENT_STATE_CONFLICT -> HttpStatus.CONFLICT;
        };
        return toResponse(status, exception.errorCode().name(), exception.getMessage(), response);
    }

    /**
     * The linked requirement lifecycle is driven inside task commands; surface its stable
     * codes as conflicts instead of opaque server errors.
     */
    @ExceptionHandler(RequirementDomainException.class)
    public ResponseEntity<ProblemDetail> requirementLifecycleFailure(
            RequirementDomainException exception, HttpServletResponse response) {
        return toResponse(HttpStatus.CONFLICT, exception.errorCode().name(), exception.getMessage(), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException exception, HttpServletResponse response) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("request body is invalid");
        return toResponse(HttpStatus.BAD_REQUEST, "INVALID_TASK_REQUEST", detail, response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadableBody(HttpMessageNotReadableException exception, HttpServletResponse response) {
        return toResponse(HttpStatus.BAD_REQUEST, "INVALID_TASK_REQUEST", "request body is invalid", response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> mismatchedArgument(MethodArgumentTypeMismatchException exception, HttpServletResponse response) {
        return toResponse(HttpStatus.BAD_REQUEST, "INVALID_TASK_REQUEST", "invalid request parameter: " + exception.getName(), response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(IllegalArgumentException exception, HttpServletResponse response) {
        return toResponse(HttpStatus.BAD_REQUEST, "INVALID_TASK_REQUEST", exception.getMessage(), response);
    }

    /**
     * Catch-all safety net: any unmapped failure surfaces as a stable 500 with a fixed code
     * and a correlation id instead of leaking a container-generated error page.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpectedFailure(Exception exception, HttpServletResponse response) {
        return toResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "TASK_UNEXPECTED_FAILURE",
                "unexpected task failure: " + exception.getClass().getSimpleName(),
                response);
    }

    private ResponseEntity<ProblemDetail> toResponse(
            HttpStatus status, String code, String detail, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty(TRACE_HEADER, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        return ResponseEntity.status(status).body(problem);
    }
}
