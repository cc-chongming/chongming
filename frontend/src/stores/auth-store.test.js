import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ReviewApiError } from '../api/review-api';
import { createAuthStore } from './auth-store';

const STORAGE_KEY = 'chongming-auth';
const originalLocalStorage = globalThis.localStorage;

function makeJwt(payload) {
    const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
    return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(payload)}.test-signature`;
}

const validToken = makeJwt({ sub: 'alice', exp: 4102444800 });
const expiredToken = makeJwt({ sub: 'alice', exp: 1000 });
const sessionUser = { username: 'alice', displayName: 'Alice', role: 'PRODUCT_MANAGER' };

function installLocalStorage() {
    const backing = new Map();
    globalThis.localStorage = {
        getItem: (key) => (backing.has(key) ? backing.get(key) : null),
        setItem: (key, value) => backing.set(key, String(value)),
        removeItem: (key) => backing.delete(key)
    };
    return backing;
}

function seedSession(backing, token, user = sessionUser) {
    backing.set(STORAGE_KEY, JSON.stringify({ token, user }));
}

function createApi(overrides = {}) {
    return {
        login: async () => ({ token: validToken, user: sessionUser }),
        register: async () => ({ token: validToken, user: sessionUser }),
        ...overrides
    };
}

beforeEach(() => {
    installLocalStorage();
});

afterEach(() => {
    if (originalLocalStorage === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = originalLocalStorage;
});

describe('auth store', () => {
    it('persists token and user after a successful login', async () => {
        const backing = installLocalStorage();
        const store = createAuthStore({ api: createApi() });

        const result = await store.login('alice', 'secret');

        expect(result.token).toBe(validToken);
        expect(store.token.value).toBe(validToken);
        expect(store.currentUser.value).toEqual(sessionUser);
        expect(JSON.parse(backing.get(STORAGE_KEY))).toEqual({ token: validToken, user: sessionUser });
    });

    it('propagates login failures without persisting a session', async () => {
        const backing = installLocalStorage();
        const api = createApi({
            login: async () => {
                throw new ReviewApiError('用户名或密码错误', { status: 401, errorCode: 'AUTH_BAD_CREDENTIALS' });
            }
        });
        const store = createAuthStore({ api });

        await expect(store.login('alice', 'wrong')).rejects.toMatchObject({
            status: 401, errorCode: 'AUTH_BAD_CREDENTIALS'
        });
        expect(store.token.value).toBeNull();
        expect(store.currentUser.value).toBeNull();
        expect(backing.has(STORAGE_KEY)).toBe(false);
    });

    it('signs the user in automatically after a successful registration', async () => {
        const backing = installLocalStorage();
        const calls = [];
        const api = createApi({
            register: async (username, password, displayName, role) => {
                calls.push({ username, password, displayName, role });
                return { token: validToken, user: { username, displayName, role: role ?? 'DEVELOPER' } };
            }
        });
        const store = createAuthStore({ api });

        await store.register('bob', 'secret', 'Bob Chen', 'PRODUCT_MANAGER');

        expect(calls).toEqual([{ username: 'bob', password: 'secret', displayName: 'Bob Chen', role: 'PRODUCT_MANAGER' }]);
        expect(store.currentUser.value).toEqual({ username: 'bob', displayName: 'Bob Chen', role: 'PRODUCT_MANAGER' });
        expect(backing.get(STORAGE_KEY)).toContain('Bob Chen');
    });

    it('omits the role from the registration request when none is selected', async () => {
        installLocalStorage();
        const calls = [];
        const api = createApi({
            register: async (username, password, displayName, role) => {
                calls.push({ username, password, displayName, role });
                return { token: validToken, user: { username, displayName, role: 'DEVELOPER' } };
            }
        });
        const store = createAuthStore({ api });

        await store.register('bob', 'secret', 'Bob Chen');

        expect(calls).toEqual([{ username: 'bob', password: 'secret', displayName: 'Bob Chen', role: undefined }]);
    });

    it.each([
        ['ADMIN', true],
        ['PRODUCT_MANAGER', true],
        ['PROJECT_MANAGER', true],
        ['DEVELOPER', false],
        ['USER', false],
        ['PRODUCT', false],
        [undefined, false]
    ])('derives canCreateRequirement from session role %s -> %s', async (role, expected) => {
        installLocalStorage();
        const api = createApi({
            login: async () => ({ token: validToken, user: { username: 'alice', displayName: 'Alice', role } })
        });
        const store = createAuthStore({ api });

        expect(store.canCreateRequirement.value).toBe(false);
        await store.login('alice', 'secret');

        expect(store.canCreateRequirement.value).toBe(expected);

        store.logout();
        expect(store.canCreateRequirement.value).toBe(false);
    });

    it('clears the in-memory session and the persisted storage on logout', async () => {
        const backing = installLocalStorage();
        const store = createAuthStore({ api: createApi() });
        await store.login('alice', 'secret');

        store.logout();

        expect(store.token.value).toBeNull();
        expect(store.currentUser.value).toBeNull();
        expect(backing.has(STORAGE_KEY)).toBe(false);
    });

    it('restores a persisted valid session on creation and on demand', () => {
        const backing = installLocalStorage();
        seedSession(backing, validToken);

        const store = createAuthStore({ api: createApi() });

        expect(store.token.value).toBe(validToken);
        expect(store.currentUser.value).toEqual(sessionUser);
        expect(store.restore()).toBe(true);
        expect(store.isTokenValid()).toBe(true);
    });

    it('drops an expired persisted token during restore', () => {
        const backing = installLocalStorage();
        seedSession(backing, expiredToken);

        const store = createAuthStore({ api: createApi() });

        expect(store.token.value).toBeNull();
        expect(store.currentUser.value).toBeNull();
        expect(backing.has(STORAGE_KEY)).toBe(false);
        expect(store.restore()).toBe(false);
    });

    it('restores nothing when storage is empty without failing', () => {
        installLocalStorage();

        const store = createAuthStore({ api: createApi() });

        expect(store.restore()).toBe(false);
        expect(store.isTokenValid()).toBe(false);
    });

    it('treats undecodable opaque tokens as valid locally but rejects expired JWTs', () => {
        const backing = installLocalStorage();
        seedSession(backing, 'opaque-token');
        const store = createAuthStore({ api: createApi() });

        expect(store.isTokenValid()).toBe(true);

        seedSession(backing, expiredToken);
        store.restore();
        expect(store.isTokenValid()).toBe(false);

        seedSession(backing, validToken);
        store.restore();
        // The `nowMs` probe must honor an injected clock for expiry checks.
        expect(store.isTokenValid(4102444801 * 1000)).toBe(false);
        expect(store.isTokenValid(4102444799 * 1000)).toBe(true);
    });
});
