package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewIntakeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Converts Markdown intake validation failures into stable HTTP problem responses.
 *
 * @author wangli
 */
@RestControllerAdvice(assignableTypes = ReviewCommandController.class)
public class ReviewIntakeExceptionHandler {

    /**
     * Produces an RFC 9457 problem body for an intake validation failure.
     *
     * @param exception validated intake exception
     * @return HTTP problem body with a stable machine-readable code
     */
    @ExceptionHandler(ReviewIntakeException.class)
    public ProblemDetail handleReviewIntakeException(ReviewIntakeException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage());
    }

    /**
     * Reports a missing multipart file using the intake error contract.
     *
     * @param exception missing multipart part
     * @return HTTP 400 problem body
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail handleMissingPart(MissingServletRequestPartException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "MISSING_MULTIPART_PART",
                "Required multipart part is missing: " + exception.getRequestPartName());
    }

    /**
     * Reports a missing request parameter using the intake error contract.
     *
     * @param exception missing request parameter
     * @return HTTP 400 problem body
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "MISSING_REQUEST_PARAMETER",
                "Required request parameter is missing: " + exception.getParameterName());
    }

    /**
     * Reports a multipart upload that exceeded the configured request size limit.
     *
     * @param exception multipart size failure
     * @return HTTP 413 problem body
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handlePayloadTooLarge(MaxUploadSizeExceededException exception) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Uploaded requirement file is too large");
    }

    /**
     * Shields callers from internal failure details while preserving the documented problem shape.
     *
     * @param exception unexpected intake failure
     * @return HTTP 500 problem body
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedFailure(Exception exception) {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTAKE_UNEXPECTED_FAILURE",
                "Unexpected failure while accepting review intake");
    }
    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Review intake rejected");
        problem.setProperty("code", code);
        return problem;
    }
}