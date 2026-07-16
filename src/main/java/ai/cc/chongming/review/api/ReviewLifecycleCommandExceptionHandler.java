package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewCommandService;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * [AIREVIEW-PLAN-010#1.6,#1.7] Maps lifecycle command failures to the stable 404/409/422 API contract.
 *
 * @author wangli
 */
@RestControllerAdvice(assignableTypes = ReviewLifecycleCommandController.class)
public class ReviewLifecycleCommandExceptionHandler {

    @ExceptionHandler(ReviewCommandService.ReviewCommandNotFoundException.class)
    public ProblemDetail reviewNotFound(ReviewCommandService.ReviewCommandNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review was not found");
    }

    @ExceptionHandler(ReviewDomainException.class)
    public ProblemDetail domainFailure(ReviewDomainException exception) {
        HttpStatus status = exception.errorCode() == ReviewErrorCode.VERSION_CONFLICT
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return problem(status, exception.errorCode().name(), exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class, MethodArgumentNotValidException.class})
    public ProblemDetail invalidCommand(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_COMMAND", "Review command request is invalid");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpectedFailure(Exception exception) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "COMMAND_UNEXPECTED_FAILURE",
                "Unexpected failure while processing review command");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Review command rejected");
        problem.setProperty("code", code);
        return problem;
    }
}
