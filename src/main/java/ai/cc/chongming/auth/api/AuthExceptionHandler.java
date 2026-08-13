package ai.cc.chongming.auth.api;

import ai.cc.chongming.auth.config.AuthModuleEnabledCondition;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Returns stable client errors for authentication endpoints, mirroring the review domain's
 * ProblemDetail contract: a {@code code} property plus an {@code x-trace-id} correlation value
 * (also exposed as a response header).
 *
 * @author wangli
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
@Conditional(AuthModuleEnabledCondition.class)
public class AuthExceptionHandler {

    /** Correlation header echoed on every authentication error response. */
    public static final String TRACE_HEADER = "x-trace-id";

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ProblemDetail> authFailure(AuthException exception, HttpServletResponse response) {
        HttpStatus status = switch (exception.errorCode()) {
            case INVALID_CREDENTIAL, UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case USERNAME_TAKEN -> HttpStatus.CONFLICT;
        };
        return toResponse(status, exception.errorCode().name(), exception.getMessage(), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException exception, HttpServletResponse response) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("request body is invalid");
        return toResponse(HttpStatus.BAD_REQUEST, "INVALID_AUTH_REQUEST", detail, response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadableBody(HttpMessageNotReadableException exception, HttpServletResponse response) {
        return toResponse(HttpStatus.BAD_REQUEST, "INVALID_AUTH_REQUEST", "request body is invalid", response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(IllegalArgumentException exception, HttpServletResponse response) {
        return toResponse(HttpStatus.BAD_REQUEST, "INVALID_AUTH_REQUEST", exception.getMessage(), response);
    }

    private ResponseEntity<ProblemDetail> toResponse(
            HttpStatus status, String code, String detail, HttpServletResponse response) {
        String traceId = UUID.randomUUID().toString();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty(TRACE_HEADER, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * Builds the same ProblemDetail contract outside a controller invocation, used by
     * {@link AuthJwtFilter} when it short-circuits a request before dispatch.
     *
     * @param errorCode stable authentication error code
     * @param detail    human-readable explanation
     * @return response carrying the ProblemDetail body and correlation header
     */
    public static ResponseEntity<ProblemDetail> unauthorizedResponse(AuthErrorCode errorCode, String detail) {
        String traceId = UUID.randomUUID().toString();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setProperty("code", errorCode.name());
        problem.setProperty(TRACE_HEADER, traceId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(TRACE_HEADER, traceId)
                .header("WWW-Authenticate", "Bearer")
                .body(problem);
    }
}
