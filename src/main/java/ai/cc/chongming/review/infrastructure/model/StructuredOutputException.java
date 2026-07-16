package ai.cc.chongming.review.infrastructure.model;

import java.util.Objects;

/**
 * Stable, auditable structured-output failure that never promotes free text into a business object.
 *
 * @author wangli
 */
public class StructuredOutputException extends RuntimeException {

    private final Code code;

    public StructuredOutputException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public StructuredOutputException(Code code, String message) {
        this(code, message, null);
    }

    public Code code() {
        return code;
    }

    /**
     * Structured-output failure categories.
     *
     * @author wangli
     */
    public enum Code {
        MALFORMED_JSON,
        SCHEMA_VIOLATION,
        REPAIR_FAILED
    }
}
