package ai.cc.chongming.review.application;

import java.util.Objects;

/**
 * Reports a stable rejection while resolving or freezing an authorized repository.
 *
 * @author wangli
 */
public class RepositoryAccessException extends RuntimeException {

    private final Code code;

    public RepositoryAccessException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public RepositoryAccessException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public Code code() {
        return code;
    }

    /**
     * Stable repository-access failure classes that do not expose host filesystem details.
     *
     * @author wangli
     */
    public enum Code {
        REPOSITORY_NOT_CONFIGURED,
        REPOSITORY_NOT_FOUND,
        REPOSITORY_PATH_UNSAFE,
        REPOSITORY_NOT_GIT,
        GIT_METADATA_UNAVAILABLE,
        SYMLINK_NOT_ALLOWED,
        REPOSITORY_READ_BUDGET_EXHAUSTED,
        SNAPSHOT_ALREADY_EXISTS,
        SNAPSHOT_FAILED,
        /** [AIREVIEW-PLAN-024] The requested fileRef is not granted to this role; not retryable. */
        FILE_REF_NOT_GRANTED,
        /** [AIREVIEW-PLAN-024] The fileRef does not belong to the current review snapshot; not retryable. */
        FILE_NOT_IN_SNAPSHOT,
        /** [AIREVIEW-PLAN-024] The requested line range is invalid; not retryable. */
        INVALID_LINE_RANGE,
        /** [AIREVIEW-PLAN-024] The role's repository read budget is exhausted. */
        READ_BUDGET_EXHAUSTED,
        /** [AIREVIEW-PLAN-028] Cloning or updating the administrator-configured remote repository failed. */
        REMOTE_FETCH_FAILED,
        /** [AIREVIEW-PLAN-028] The remote repository rejected the configured credentials; not retryable as-is. */
        REMOTE_AUTH_FAILED
    }
}
