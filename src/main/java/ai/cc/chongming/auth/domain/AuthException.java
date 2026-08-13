package ai.cc.chongming.auth.domain;

import java.util.Objects;

/**
 * Carries a stable authentication error code so the API layer can map failures to
 * deterministic HTTP statuses and client-facing codes.
 *
 * @author wangli
 */
public final class AuthException extends RuntimeException {

    private final AuthErrorCode errorCode;

    public AuthException(AuthErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public AuthErrorCode errorCode() {
        return errorCode;
    }
}
