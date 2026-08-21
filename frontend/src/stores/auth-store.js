import { computed, reactive } from 'vue';
import { authApi } from '../api/auth-api';
import {
    clearAuthSession,
    isTokenExpired,
    readAuthSession,
    saveAuthSession
} from '../services/auth-token';
import { canCreateRequirements } from '../services/roles';

/**
 * Auth store following the review-store factory convention: dependencies (api) are injected
 * so unit tests can substitute in-memory fakes. Token + user are persisted under the
 * `chongming-auth` key through the shared auth-token module, which also backs the API
 * client and SSE services without creating import cycles.
 */
export function createAuthStore({ api = authApi } = {}) {
    const state = reactive({
        token: null,
        user: null
    });

    const token = computed(() => state.token);
    const currentUser = computed(() => state.user);
    // [AIREVIEW-PLAN-027] Derived requirement-creation permission based on the canonical session role.
    const canCreateRequirement = computed(() => canCreateRequirements(state.user?.role));

    function applySession(session) {
        state.token = session?.token ?? null;
        state.user = session?.user ?? null;
    }

    /** Local check only: token present and, when decodable as JWT, not past its `exp` claim. */
    function isTokenValid(nowMs = Date.now()) {
        if (!state.token) return false;
        return !isTokenExpired(state.token, nowMs);
    }

    /** Re-reads the persisted session and drops it when the token is already expired. */
    function restore() {
        const session = readAuthSession();
        if (!session || isTokenExpired(session.token)) {
            clearAuthSession();
            applySession(null);
            return false;
        }
        applySession(session);
        return true;
    }

    async function login(username, password) {
        const result = await api.login(username, password);
        saveAuthSession({ token: result.token, user: result.user });
        applySession({ token: result.token, user: result.user });
        return result;
    }

    async function register(username, password, displayName, role, uid, contacts) {
        const result = await api.register(username, password, displayName, role, uid, contacts);
        saveAuthSession({ token: result.token, user: result.user });
        applySession({ token: result.token, user: result.user });
        return result;
    }

    function logout() {
        clearAuthSession();
        applySession(null);
    }

    restore();

    return {
        state, token, currentUser, canCreateRequirement,
        login, register, logout, restore, isTokenValid
    };
}

/** Shared instance used by the router guard, the app shell and the auth views. */
export const authStore = createAuthStore();
