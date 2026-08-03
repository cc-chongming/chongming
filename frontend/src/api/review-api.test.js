import { afterEach, describe, expect, it, vi } from 'vitest';
import { reviewApi } from './review-api';

const reviewId = '11111111-1111-1111-1111-111111111111';
const originalFetch = globalThis.fetch;

function response(body) {
    return new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } });
}

afterEach(() => {
    globalThis.fetch = originalFetch;
});

describe('review lifecycle API', () => {
    it('sends start, cancel and retry using the frozen command contract', async () => {
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(response({ reviewId, attemptNo: 1, version: 3, stage: 'PLANNING', replayed: false }))
            .mockResolvedValueOnce(response({ reviewId, attemptNo: 1, version: 5, replayed: false }))
            .mockResolvedValueOnce(response({ reviewId, previousAttempt: 1, attemptNo: 2, version: 6, replayed: false }));
        globalThis.fetch = fetchMock;

        await reviewApi.startReview(reviewId, {
            expectedVersion: 0,
            idempotencyKey: 'start-001',
            userId: 'user-001',
            publicTasks: ['Review requirements'],
            changeReason: 'Initial plan',
            initialMessage: 'Begin review',
            traceId: 'trace-001'
        });
        await reviewApi.cancelReview(reviewId, 3);
        await reviewApi.retryReview(reviewId, 5);

        expect(fetchMock.mock.calls[0][0]).toBe(`/api/reviews/${reviewId}/start`);
        expect(fetchMock.mock.calls[0][1].headers).toMatchObject({
            'Content-Type': 'application/json', 'Idempotency-Key': 'start-001', 'X-Trace-Id': 'trace-001'
        });
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({ expectedVersion: 0, userId: 'user-001' });
        expect(fetchMock.mock.calls[1][0]).toBe(`/api/reviews/${reviewId}/cancel?expectedVersion=3`);
        expect(fetchMock.mock.calls[2][0]).toBe(`/api/reviews/${reviewId}/retry?expectedVersion=5`);
    });
});

describe('Context Scout preview API', () => {
    it('starts an isolated preview against the selected review attempt', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response({
            previewId: 'preview-001', runtimeId: 'runtime-001', reviewId, attemptNo: 2
        }));
        globalThis.fetch = fetchMock;

        await reviewApi.startScoutPreview(reviewId, 2, { userId: 'scout-preview', traceId: 'scout-trace-001' });

        expect(fetchMock.mock.calls[0][0]).toBe(`/api/reviews/${reviewId}/attempts/2/scout-previews`);
        expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST' });
        expect(fetchMock.mock.calls[0][1].headers).toMatchObject({
            'Content-Type': 'application/json', 'X-Trace-Id': 'scout-trace-001'
        });
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ userId: 'scout-preview' });
    });
});

describe('requirement platform API', () => {
    it('uses lifecycle and dashboard endpoints with explicit version commands', async () => {
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(response({ id: 'requirement-001', version: 0 }))
            .mockResolvedValueOnce(response({ id: 'requirement-001', reviewId, version: 1 }))
            .mockResolvedValueOnce(response({ activeReviewCount: 1 }));
        globalThis.fetch = fetchMock;

        await reviewApi.createRequirement({ title: '身份同步', description: '需求', repositoryPath: 'cx-ai', priority: 'P1' });
        await reviewApi.submitRequirement('requirement-001', { reviewId, expectedVersion: 0 });
        await reviewApi.getDashboard();

        expect(fetchMock.mock.calls[0][0]).toBe('/api/requirements');
        expect(fetchMock.mock.calls[0][1].method).toBe('POST');
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({ title: '身份同步' });
        expect(fetchMock.mock.calls[1][0]).toBe('/api/requirements/requirement-001/submit');
        expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ reviewId, expectedVersion: 0 });
        expect(fetchMock.mock.calls[2][0]).toBe('/api/dashboard');
    });
});
