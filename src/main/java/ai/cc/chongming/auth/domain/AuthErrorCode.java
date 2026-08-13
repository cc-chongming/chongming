package ai.cc.chongming.auth.domain;

/**
 * Stable error codes for authentication commands and bearer-token validation.
 *
 * @author wangli
 */
public enum AuthErrorCode {
    INVALID_CREDENTIAL,
    USERNAME_TAKEN,
    UNAUTHENTICATED,
    FORBIDDEN
}
