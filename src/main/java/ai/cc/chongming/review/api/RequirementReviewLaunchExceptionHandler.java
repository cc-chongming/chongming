package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.RequirementReviewLaunchException;
import ai.cc.chongming.review.application.ReviewIntakeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * [AIREVIEW-PLAN-023#3] Maps launch orchestration failures to stable, non-sensitive problem details.
 *
 * @author zyj
 */
@RestControllerAdvice(assignableTypes = RequirementCommandController.class)
public class RequirementReviewLaunchExceptionHandler {

    @ExceptionHandler(RequirementReviewLaunchException.class)
    public ProblemDetail launchFailure(RequirementReviewLaunchException exception) {
        ProblemDetail detail = problem(exception.status().value(), exception.code(), exception.getMessage());
        detail.setProperty("phase", exception.phase());
        detail.setProperty("recoverable", exception.recoverable());
        if (exception.existingReviewId() != null) {
            detail.setProperty("existingReviewId", exception.existingReviewId());
        }
        return detail;
    }

    @ExceptionHandler(ReviewIntakeException.class)
    public ProblemDetail intakeFailure(ReviewIntakeException exception) {
        return problem(exception.status().value(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail missingPart(MissingServletRequestPartException exception) {
        return problem(400, "MISSING_MULTIPART_PART", "Required multipart part is missing: " + exception.getRequestPartName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail missingParameter(MissingServletRequestParameterException exception) {
        return problem(400, "MISSING_REQUEST_PARAMETER", "Required request parameter is missing: " + exception.getParameterName());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail missingHeader(MissingRequestHeaderException exception) {
        return problem(400, "MISSING_REQUEST_HEADER", "Required request header is missing: " + exception.getHeaderName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail payloadTooLarge(MaxUploadSizeExceededException exception) {
        return problem(413, "PAYLOAD_TOO_LARGE", "Uploaded requirement file is too large");
    }

    private ProblemDetail problem(int status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), message);
        detail.setTitle("Requirement review launch rejected");
        detail.setProperty("code", code);
        return detail;
    }
}
