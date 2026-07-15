package ai.cc.chongming.review.application;

import org.springframework.http.HttpStatus;

/**
 * Signals a client-visible failure while accepting a requirement document.
 *
 * @author wangli
 */
public class ReviewIntakeException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ReviewIntakeException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static ReviewIntakeException invalid(String code, String message) {
        return new ReviewIntakeException(code, HttpStatus.UNPROCESSABLE_CONTENT, message);
    }

    public static ReviewIntakeException badRequest(String code, String message) {
        return new ReviewIntakeException(code, HttpStatus.BAD_REQUEST, message);
    }
}
