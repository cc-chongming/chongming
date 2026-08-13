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

    register(username, password, displayName) {
        return request('/api/auth/register', {
            method: 'POST',
            ...jsonBody({ username, password, displayName })
        });
    },

    me() {
        return request('/api/auth/me');
    }
};
