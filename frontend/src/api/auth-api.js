/**
 * Thin REST client for the authentication endpoints (login / register / me).
 * Reuses the shared `request()` pipeline from review-api so ProblemDetail parsing,
 * `ReviewApiError` semantics and Bearer-token injection stay identical.
 */
import { request } from './review-api';

function jsonBody(value) {
    return {
        body: JSON.stringify(value),
        headers: { 'Content-Type': 'application/json' }
    };
}

export const authApi = {
    login(username, password) {
        return request('/api/auth/login', { method: 'POST', ...jsonBody({ username, password }) });
    },

    /**
     * [AIREVIEW-PLAN-027] The optional `role` (PRODUCT_MANAGER/PROJECT_MANAGER/DEVELOPER)
     * is only sent when provided; the backend defaults to DEVELOPER and rejects ADMIN.
     */
    register(username, password, displayName, role) {
        const body = { username, password, displayName };
        if (role) body.role = role;
        return request('/api/auth/register', {
            method: 'POST',
            ...jsonBody(body)
        });
    },

    me() {
        return request('/api/auth/me');
    },

    /** Task-center user directory (ADMIN only): [{username, displayName, role}]. */
    listUsers() {
        return request('/api/users');
    }
};
