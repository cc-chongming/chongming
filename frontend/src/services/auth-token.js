/**
 * Shared auth-token access module.
 * Sits below the API client, the SSE services and the auth store so that none of them
 * need to import each other, which avoids a review-api <-> auth-store circular dependency.
 * The session (JWT token + user profile) is persisted under the `chongming-auth` key.
 */

const STORAGE_KEY = 'chongming-auth';

function storage() {
    return globalThis.localStorage ?? { getItem: () => null, setItem: () => {}, removeItem: () => {} };
}

export function readAuthSession() {
    try {
        const raw = storage().getItem(STORAGE_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed.token !== 'string' || !parsed.token) return null;
        const user = parsed.user && typeof parsed.user === 'object' ? parsed.user : null;
        return { token: parsed.token, user };
    } catch {
        return null;
    }
}

export function saveAuthSession({ token, user }) {
    try {
        storage().setItem(STORAGE_KEY, JSON.stringify({ token, user: user ?? null }));
    } catch {
        // Persistence failures are tolerated; the session stays in memory only.
    }
}

export function clearAuthSession() {
    try {
        storage().removeItem(STORAGE_KEY);
    } catch {
        // Tolerated: clearing in-memory state still happens in the caller.
    }
}

export function getAuthToken() {
    return readAuthSession()?.token ?? null;
}

/**
 * Decodes the JWT payload with plain base64url handling (no signature verification;
 * verification remains the server's responsibility). Returns null for non-JWT tokens.
 */
export function decodeJwtPayload(token) {
    if (typeof token !== 'string' || typeof atob !== 'function') return null;
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    try {
        const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
        const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
        const bytes = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
        const payload = JSON.parse(new TextDecoder().decode(bytes));
        return payload && typeof payload === 'object' ? payload : null;
    } catch {
        return null;
    }
}

/**
 * Local expiry check based on the JWT `exp` claim. Tokens that cannot be decoded
 * (opaque tokens) are treated as valid and left to the server to reject.
 */
export function isTokenExpired(token, nowMs = Date.now()) {
    const payload = decodeJwtPayload(token);
    if (!payload || !Number.isFinite(Number(payload.exp))) return false;
    return Number(payload.exp) * 1000 <= nowMs;
}

/**
 * Usability probe for SSE reconnect decisions: a missing token passes through
 * (keeps legacy anonymous behavior), while a present-but-expired token stops retries.
 */
export function isStoredTokenUsable(nowMs = Date.now()) {
    const token = getAuthToken();
    if (!token) return true;
    return !isTokenExpired(token, nowMs);
}

/** Appends `access_token=<token>` for EventSource streams, which cannot send headers. */
export function withAuthToken(url) {
    const token = getAuthToken();
    if (!token) return url;
    const separator = url.includes('?') ? '&' : '?';
    return `${url}${separator}access_token=${encodeURIComponent(token)}`;
}

/** Hash-router friendly redirect used by the 401 handler and expired SSE streams. */
export function redirectToLogin() {
    const location = globalThis.location;
    if (!location) return;
    // Preserve the deep link so the login guard can bounce the user back after signing in.
    const current = (location.hash ?? '').replace(/^#/, '').split('?')[0];
    const onAuthPage = !current || current === '/'
        || current.startsWith('/login') || current.startsWith('/register');
    location.hash = onAuthPage ? '#/login' : `#/login?redirect=${encodeURIComponent(current)}`;
}
