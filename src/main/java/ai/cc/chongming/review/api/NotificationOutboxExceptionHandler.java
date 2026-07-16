package ai.cc.chongming.review.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * [AIREVIEW-PLAN-011#1.5,#1.7] Stable API error contract for notification status and retry commands.
 *
 * @author wangli
 */
@RestControllerAdvice(assignableTypes = NotificationOutboxController.class)
public class NotificationOutboxExceptionHandler {

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ProblemDetail notFound(java.util.NoSuchElementException exception) {
        return problem(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, "NOTIFICATION_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ProblemDetail forbidden(SecurityException exception) {
        return problem(HttpStatus.FORBIDDEN, "NOTIFICATION_FORBIDDEN", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("code", code);
        return detail;
    }
}
