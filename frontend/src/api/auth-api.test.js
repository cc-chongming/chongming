import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReviewApiError } from './review-api';
import { authApi } from './auth-api';

const originalFetch = globalThis.fetch;
const originalLocalStorage = globalThis.localStorage;

function response(body, { status = 200, headers = {} } = {}) {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'content-type': 'application/json', ...headers }
    });
}

afterEach(() => {
    globalThis.fetch = originalFetch;
    if (originalLocalStorage === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = originalLocalStorage;
});

describe('auth API', () => {
    it('posts login credentials and parses the signed session', async () => {
        const session = { token: 'jwt-token', user: { username: 'alice', displayName: 'Alice', role: 'PRODUCT_MANAGER' } };
        const fetchMock = vi.fn().mockResolvedValue(response(session));
        globalThis.fetch = fetchMock;

        const result = await authApi.login('alice', 'secret');

        expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/login');
        expect(fetchMock.mock.calls[0][1].method).toBe('POST');
        expect(fetchMock.mock.calls[0][1].headers).toMatchObject({ 'Content-Type': 'application/json' });
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ username: 'alice', password: 'secret' });
        expect(result).toEqual(session);
    });

    it('posts register payloads including the display name', async () => {
        const session = { token: 'jwt-token', user: { username: 'bob', displayName: 'Bob', role: 'DEVELOPER' } };
        const fetchMock = vi.fn().mockResolvedValue(response(session));
        globalThis.fetch = fetchMock;

        const result = await authApi.register('bob', 'secret', 'Bob Chen');

        expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/register');
        expect(fetchMock.mock.calls[0][1].method).toBe('POST');
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
            username: 'bob', password: 'secret', displayName: 'Bob Chen'
        });
        expect(result).toEqual(session);
    });

    it('includes the selected role in the register payload when provided', async () => {
        const session = { token: 'jwt-token', user: { username: 'bob', displayName: 'Bob', role: 'PRODUCT_MANAGER' } };
        const fetchMock = vi.fn().mockResolvedValue(response(session));
        globalThis.fetch = fetchMock;

        await authApi.register('bob', 'secret', 'Bob Chen', 'PRODUCT_MANAGER');

        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
            username: 'bob', password: 'secret', displayName: 'Bob Chen', role: 'PRODUCT_MANAGER'
        });
    });

    it('includes the company uid in the register payload when provided [AIREVIEW-PLAN-025]', async () => {
        const session = {
            token: 'jwt-token',
            user: { username: 'bob', displayName: 'Bob', role: 'DEVELOPER', uid: 'corp-10086' }
        };
        const fetchMock = vi.fn().mockResolvedValue(response(session));
        globalThis.fetch = fetchMock;

        const result = await authApi.register('bob', 'secret', 'Bob Chen', 'DEVELOPER', 'corp-10086');

        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
            username: 'bob', password: 'secret', displayName: 'Bob Chen', role: 'DEVELOPER', uid: 'corp-10086'
        });
        expect(result.user.uid).toBe('corp-10086');
    });

    it('omits the company uid from the register payload when absent [AIREVIEW-PLAN-025]', async () => {
        const session = { token: 'jwt-token', user: { username: 'bob', displayName: 'Bob', role: 'DEVELOPER' } };
        const fetchMock = vi.fn().mockResolvedValue(response(session));
        globalThis.fetch = fetchMock;

        await authApi.register('bob', 'secret', 'Bob Chen', 'DEVELOPER');

        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
            username: 'bob', password: 'secret', displayName: 'Bob Chen', role: 'DEVELOPER'
        });
    });

    it('queries the current user profile with a stored Bearer token', async () => {
        const backing = new Map([[
            'chongming-auth',
            JSON.stringify({ token: 'stored-token', user: { username: 'alice' } })
        ]]);
        globalThis.localStorage = {
            getItem: (key) => (backing.has(key) ? backing.get(key) : null),
            setItem: (key, value) => backing.set(key, String(value)),
            removeItem: (key) => backing.delete(key)
        };
        const fetchMock = vi.fn().mockResolvedValue(response({ username: 'alice', displayName: 'Alice' }));
        globalThis.fetch = fetchMock;

        const profile = await authApi.me();

        expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/me');
        expect(fetchMock.mock.calls[0][1].method).toBe('GET');
        expect(fetchMock.mock.calls[0][1].headers).toMatchObject({ Authorization: 'Bearer stored-token' });
        expect(profile).toEqual({ username: 'alice', displayName: 'Alice' });
    });

    it('throws a ReviewApiError with the ProblemDetail contract on rejected credentials', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response(
            { title: 'Unauthorized', detail: '用户名或密码错误', code: 'AUTH_BAD_CREDENTIALS' },
            { status: 401, headers: { 'x-trace-id': 'trace-auth-401' } }
        ));
        globalThis.fetch = fetchMock;

        await expect(authApi.login('alice', 'wrong')).rejects.toMatchObject({
            name: 'ReviewApiError',
            message: '用户名或密码错误',
            status: 401,
            errorCode: 'AUTH_BAD_CREDENTIALS',
            traceId: 'trace-auth-401'
        });
    });

    it('surfaces duplicate-username conflicts as ReviewApiError on register', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response(
            { title: 'Conflict', detail: '用户名已存在', code: 'AUTH_USERNAME_TAKEN' },
            { status: 409, headers: { 'x-trace-id': 'trace-auth-409' } }
        ));
        globalThis.fetch = fetchMock;

        const error = await authApi.register('alice', 'secret', 'Alice').catch((value) => value);

        expect(error).toBeInstanceOf(ReviewApiError);
        expect(error.status).toBe(409);
        expect(error.errorCode).toBe('AUTH_USERNAME_TAKEN');
        expect(error.message).toBe('用户名已存在');
    });
});
