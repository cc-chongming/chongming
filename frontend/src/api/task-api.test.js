import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReviewApiError } from './review-api';
import { taskApi } from './task-api';

const taskId = '22222222-2222-2222-2222-222222222222';
const requirementId = '11111111-1111-1111-1111-111111111111';
const originalFetch = globalThis.fetch;

function response(body, status = 200) {
    return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

afterEach(() => {
    globalThis.fetch = originalFetch;
});

describe('task center API', () => {
    it('lists tasks with status, assignee, mine and pagination query parameters', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response({ items: [], page: 2, size: 10, total: 0 }));
        globalThis.fetch = fetchMock;

        await taskApi.listTasks({ status: 'DEVELOPING', assignee: 'dev01', mine: true, page: 2, size: 10 });

        expect(fetchMock.mock.calls[0][0]).toBe('/api/tasks?status=DEVELOPING&assignee=dev01&mine=true&page=2&size=10');
        expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'GET' });
    });

    it('keeps the default pagination and omits empty filter parameters', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response({ items: [], page: 1, size: 20, total: 0 }));
        globalThis.fetch = fetchMock;

        await taskApi.listTasks();

        expect(fetchMock.mock.calls[0][0]).toBe('/api/tasks?page=1&size=20');
    });

    it('supports requirementId filtering on the list endpoint', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response({ items: [], page: 1, size: 20, total: 0 }));
        globalThis.fetch = fetchMock;

        await taskApi.listTasks({ requirementId });

        expect(fetchMock.mock.calls[0][0]).toBe(`/api/tasks?requirementId=${requirementId}&page=1&size=20`);
    });

    it('loads a single task view', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response({ taskId, status: 'DEVELOPING', version: 3 }));
        globalThis.fetch = fetchMock;

        const task = await taskApi.getTask(taskId);

        expect(fetchMock.mock.calls[0][0]).toBe(`/api/tasks/${taskId}`);
        expect(task).toMatchObject({ taskId, status: 'DEVELOPING' });
    });

    it('sends dispatch and acceptance commands with explicit optimistic-lock versions', async () => {
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(response({ taskId, status: 'DEVELOPING', version: 1 }))
            .mockResolvedValueOnce(response({ taskId, status: 'PENDING_ACCEPTANCE', version: 2 }))
            .mockResolvedValueOnce(response({ taskId, status: 'DONE', version: 3 }))
            .mockResolvedValueOnce(response({ taskId, status: 'DEVELOPING', version: 3 }));
        globalThis.fetch = fetchMock;

        await taskApi.assign(taskId, { assigneeUsername: 'dev01', expectedVersion: 0 });
        await taskApi.submitAcceptance(taskId, { expectedVersion: 1 });
        await taskApi.accept(taskId, { note: '验收通过', expectedVersion: 2 });
        await taskApi.reject(taskId, { note: '缺少边界处理', expectedVersion: 3 });

        expect(fetchMock.mock.calls[0][0]).toBe(`/api/tasks/${taskId}/assign`);
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ assigneeUsername: 'dev01', expectedVersion: 0 });
        expect(fetchMock.mock.calls[1][0]).toBe(`/api/tasks/${taskId}/submit-acceptance`);
        expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ expectedVersion: 1 });
        expect(fetchMock.mock.calls[2][0]).toBe(`/api/tasks/${taskId}/accept`);
        expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ note: '验收通过', expectedVersion: 2 });
        expect(fetchMock.mock.calls[3][0]).toBe(`/api/tasks/${taskId}/reject`);
        expect(JSON.parse(fetchMock.mock.calls[3][1].body)).toEqual({ note: '缺少边界处理', expectedVersion: 3 });
        for (const call of fetchMock.mock.calls) {
            expect(call[1].headers).toMatchObject({ 'Content-Type': 'application/json' });
        }
    });

    it('surfaces ProblemDetail error codes such as VERSION_CONFLICT', async () => {
        const fetchMock = vi.fn().mockImplementation(() => response(
            { detail: '任务版本已刷新，请重试。', code: 'VERSION_CONFLICT' }, 409
        ));
        globalThis.fetch = fetchMock;

        await expect(taskApi.assign(taskId, { assigneeUsername: 'dev01', expectedVersion: 0 }))
            .rejects.toMatchObject({ errorCode: 'VERSION_CONFLICT', status: 409 });

        try {
            await taskApi.assign(taskId, { assigneeUsername: 'dev01', expectedVersion: 0 });
        } catch (requestError) {
            expect(requestError).toBeInstanceOf(ReviewApiError);
            expect(requestError.message).toBe('任务版本已刷新，请重试。');
        }
    });
});
