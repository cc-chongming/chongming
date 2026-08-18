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
    FORBIDDEN,
    /** [AIREVIEW-PLAN-025] The company-internal uid is already bound to another account. */
    UID_TAKEN
}
