package ai.cc.chongming.review.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * [AIREVIEW-PLAN-011#1.2] Stable error mapping for human review draft commands.
 *
 * @author wangli
 */
@RestControllerAdvice(assignableTypes = {HumanReviewController.class, HumanGateDecisionController.class})
public class HumanReviewExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail notFound(NoSuchElementException exception) {
        return problem(HttpStatus.NOT_FOUND, "HUMAN_REVIEW_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, "HUMAN_REVIEW_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_HUMAN_REVIEW_DRAFT", exception.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ProblemDetail forbidden(SecurityException exception) {
        return problem(HttpStatus.FORBIDDEN, "HUMAN_REVIEW_FORBIDDEN", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("code", code);
        return detail;
    }
}
