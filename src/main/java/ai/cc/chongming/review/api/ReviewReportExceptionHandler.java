package ai.cc.chongming.review.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * [AIREVIEW-PLAN-011#1.4] Maps report generation and query errors to stable API responses.
 *
 * @author wangli
 */
@RestControllerAdvice(assignableTypes = ReviewReportController.class)
public class ReviewReportExceptionHandler {

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ProblemDetail notFound(java.util.NoSuchElementException exception) {
        return problem(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REPORT_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, "REPORT_UNAVAILABLE", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
