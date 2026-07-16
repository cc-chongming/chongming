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
