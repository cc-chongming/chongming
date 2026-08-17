/**
 * [AIREVIEW-PLAN-027] Canonical platform roles shared by the auth store, router guard,
 * requirement list permissions and the registration form. The backend issues exactly
 * these values in JWTs and login/register responses.
 */

/** Display labels for canonical platform roles. Legacy `USER` accounts keep a fallback label. */
export const ROLE_LABELS = Object.freeze({
    ADMIN: '管理员',
    PRODUCT_MANAGER: '产品经理',
    PROJECT_MANAGER: '项目经理',
    DEVELOPER: '开发',
    USER: '普通用户'
});

/** Resolves the display label for a role; unknown or missing values fall back to the legacy user label. */
export function roleLabel(role) {
    return ROLE_LABELS[role] ?? ROLE_LABELS.USER;
}

const REQUIREMENT_CREATOR_ROLES = new Set(['ADMIN', 'PRODUCT_MANAGER', 'PROJECT_MANAGER']);

/**
 * Only ADMIN and the two manager roles may create requirements.
 * DEVELOPER, legacy USER and any unknown value are read-only on creation.
 */
export function canCreateRequirements(role) {
    return REQUIREMENT_CREATOR_ROLES.has(role);
}

/** Roles a self-service registration may pick; ADMIN is never registrable. */
export const REGISTRABLE_ROLES = Object.freeze([
    { value: 'DEVELOPER', label: ROLE_LABELS.DEVELOPER },
    { value: 'PRODUCT_MANAGER', label: ROLE_LABELS.PRODUCT_MANAGER },
    { value: 'PROJECT_MANAGER', label: ROLE_LABELS.PROJECT_MANAGER }
]);

/** The backend defaults registration to DEVELOPER when no role is supplied. */
export const DEFAULT_REGISTRATION_ROLE = 'DEVELOPER';
