package ai.cc.chongming.review.api;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * [AIREVIEW-PLAN-021#2] Returns stable client errors for requirement commands and reads.
 *
 * @author zyj
 */
@RestControllerAdvice(assignableTypes = {RequirementCommandController.class, RequirementQueryController.class})
public class RequirementExceptionHandler {

    @ExceptionHandler(RequirementDomainException.class)
    public ProblemDetail requirementDomainFailure(RequirementDomainException exception) {
        // [AIREVIEW-PLAN-027] FORBIDDEN maps to 403 alongside the historical 404/409 contract.
        // [AIREVIEW-PLAN-029] REMOTE_SOURCE_INVALID maps to 400 for invalid online repository input.
        HttpStatus status = exception.errorCode() == RequirementErrorCode.REQUIREMENT_NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : exception.errorCode() == RequirementErrorCode.FORBIDDEN
                        ? HttpStatus.FORBIDDEN
                        : exception.errorCode() == RequirementErrorCode.REMOTE_SOURCE_INVALID
                                ? HttpStatus.BAD_REQUEST
                                : HttpStatus.CONFLICT;
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setProperty("code", exception.errorCode().name());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setProperty("code", "INVALID_REQUIREMENT_REQUEST");
        return detail;
    }
}
