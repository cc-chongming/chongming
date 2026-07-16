package ai.cc.chongming.review.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * [AIREVIEW-PLAN-010#1.3][AIREVIEW-PLAN-010#1.4] Returns a stable client error for invalid cursors.
 *
 * @author wangli
 */
@RestControllerAdvice(assignableTypes = {ReviewQueryController.class, ReviewEventController.class})
public class ReviewQueryExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setProperty("code", "INVALID_REVIEW_QUERY");
        return detail;
    }
}
