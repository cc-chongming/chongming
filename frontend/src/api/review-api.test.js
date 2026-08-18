import { afterEach, describe, expect, it, vi } from 'vitest';
import { parseAssessmentsView, reviewApi } from './review-api';

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

describe('assessment five-status API', () => {
    it('queries the assessments endpoint and keeps the server coverage counters intact', async () => {
        const payload = {
            attempt: 2,
            coverage: {
                required: 20, covered: 18, confirmed: 10, partial: 3, gap: 2, unknown: 2, notApplicable: 1,
                uncoveredCheckpoints: ['BACKEND:api_contract', 'PROJECT:milestone_alignment']
            },
            assessments: [
                {
                    role: 'FRONTEND', checkpointKey: 'ui_state_handling', status: 'UNKNOWN',
                    summary: 'UNKNOWN：当前评审快照未授予前端文件', reasonSummary: '未获得前端文件授权。',
                    evidenceIds: [], createdAt: '2026-08-11T10:00:00Z'
                },
                {
                    role: 'BACKEND', checkpointKey: 'api_contract', status: 'CONFIRMED',
                    summary: '接口契约已确认。', reasonSummary: '已读取控制器与测试。',
                    evidenceIds: ['50000000-0000-0000-0000-000000000001'], createdAt: '2026-08-11T10:01:00Z'
                }
            ]
        };
        const fetchMock = vi.fn().mockResolvedValue(response(payload));
        globalThis.fetch = fetchMock;

        const view = parseAssessmentsView(await reviewApi.getAssessments(reviewId));

        expect(fetchMock.mock.calls[0][0]).toBe(`/api/reviews/${reviewId}/assessments`);
        expect(view.attempt).toBe(2);
        expect(view.coverage).toEqual({
            required: 20, covered: 18, confirmed: 10, partial: 3, gap: 2, unknown: 2, notApplicable: 1,
            uncoveredCheckpoints: ['BACKEND:api_contract', 'PROJECT:milestone_alignment']
        });
        expect(view.assessments).toHaveLength(2);
        expect(view.assessments[0]).toMatchObject({ role: 'FRONTEND', status: 'UNKNOWN' });
        expect(view.assessments[1].evidenceIds).toEqual(['50000000-0000-0000-0000-000000000001']);
    });

    it('normalizes missing assessment payload fields with safe defaults', () => {
        expect(parseAssessmentsView(null)).toEqual({
            attempt: null,
            coverage: {
                required: 0, covered: 0, confirmed: 0, partial: 0, gap: 0, unknown: 0, notApplicable: 0,
                uncoveredCheckpoints: []
            },
            assessments: []
        });
        const sparse = parseAssessmentsView({ attempt: 1, assessments: [{ role: 'PRODUCT' }] });
        expect(sparse.assessments[0]).toMatchObject({
            checkpointKey: null, status: 'UNKNOWN', summary: '', reasonSummary: '', evidenceIds: [], createdAt: null
        });
        expect(parseAssessmentsView({}).coverage.uncoveredCheckpoints).toEqual([]);
    });
});

describe('requirement platform API', () => {
    it('uses lifecycle and dashboard endpoints with explicit version commands', async () => {
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(response({ id: 'requirement-001', version: 0 }))
            .mockResolvedValueOnce(new Response(null, { status: 204 }))
            .mockResolvedValueOnce(response({ id: 'requirement-001', reviewId, version: 1 }))
            .mockResolvedValueOnce(response({ activeReviewCount: 1 }));
        globalThis.fetch = fetchMock;

        await reviewApi.createRequirement({ title: '身份同步', description: '需求', repositoryPath: 'cx-ai', priority: 'P1' });
        await reviewApi.deleteRequirement('requirement-001', 0);
        await reviewApi.submitRequirement('requirement-001', { reviewId, expectedVersion: 0 });
        await reviewApi.getDashboard();

        expect(fetchMock.mock.calls[0][0]).toBe('/api/requirements');
        expect(fetchMock.mock.calls[0][1].method).toBe('POST');
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({ title: '身份同步' });
        expect(fetchMock.mock.calls[1][0]).toBe('/api/requirements/requirement-001?expectedVersion=0');
        expect(fetchMock.mock.calls[1][1].method).toBe('DELETE');
        expect(fetchMock.mock.calls[2][0]).toBe('/api/requirements/requirement-001/submit');
        expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ reviewId, expectedVersion: 0 });
        expect(fetchMock.mock.calls[3][0]).toBe('/api/dashboard');
    });

    it('loads safe repository options from the active server configuration', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response([
            { id: 'cx-ai', displayName: 'CX AI' }
        ]));
        globalThis.fetch = fetchMock;

        const repositories = await reviewApi.listRepositories();

        expect(fetchMock).toHaveBeenCalledWith('/api/repositories', expect.objectContaining({ method: 'GET' }));
        expect(repositories).toEqual([{ id: 'cx-ai', displayName: 'CX AI' }]);
        expect(repositories[0]).not.toHaveProperty('root');
    });

    it('launches a draft requirement through the idempotent multipart orchestration endpoint', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response({
            requirementId: 'requirement-001', reviewId, stage: 'PLANNING', phase: 'STARTED', recoverable: false
        }));
        globalThis.fetch = fetchMock;
        const requirementFile = new File(['# 需求'], 'requirement.md', { type: 'text/markdown' });

        await reviewApi.launchRequirementReview('requirement-001', {
            requirementFile,
            repositoryPath: 'cx-ai',
            branch: 'main',
            commit: 'abc123',
            submitter: 'reviewer-001',
            publicTasks: ['核对范围', '核对风险'],
            changeReason: '草稿已就绪',
            initialMessage: '开始评审',
            expectedVersion: 2,
            idempotencyKey: 'launch-001',
            traceId: 'trace-001'
        });

        const [path, options] = fetchMock.mock.calls[0];
        expect(path).toBe('/api/requirements/requirement-001/reviews');
        expect(options).toMatchObject({ method: 'POST' });
        // Tolerate extra headers (e.g. a Bearer Authorization when a session exists in localStorage).
        expect(options.headers).toMatchObject({ 'Idempotency-Key': 'launch-001', 'X-Trace-Id': 'trace-001' });
        expect(options.body).toBeInstanceOf(FormData);
        expect(options.body.get('requirementFile')).toBe(requirementFile);
        expect(options.body.get('repositoryPath')).toBe('cx-ai');
        expect(options.body.get('branch')).toBe('main');
        expect(options.body.get('commit')).toBe('abc123');
        expect(options.body.get('submitter')).toBe('reviewer-001');
        expect(options.body.get('publicTasks')).toBe('["核对范围","核对风险"]');
        expect(options.body.get('changeReason')).toBe('草稿已就绪');
        expect(options.body.get('initialMessage')).toBe('开始评审');
        expect(options.body.get('expectedVersion')).toBe('2');
    });

    // [AIREVIEW-PLAN-025] Typed Markdown travels as requirementText without a file part.
    it('sends typed Markdown as requirementText when no file is provided', async () => {
        const fetchMock = vi.fn().mockResolvedValue(response({
            reviewId, attempt: 1, snapshotHash: 'a'.repeat(64), statusUrl: `/api/reviews/${reviewId}`, reused: false
        }));
        globalThis.fetch = fetchMock;

        await reviewApi.createReview({
            requirementText: '# 需求\n手动输入的需求内容。',
            repositoryPath: 'cx-ai',
            submitter: 'reviewer-001'
        });

        const [path, options] = fetchMock.mock.calls[0];
        expect(path).toBe('/api/reviews');
        expect(options.body).toBeInstanceOf(FormData);
        expect(options.body.get('requirementText')).toBe('# 需求\n手动输入的需求内容。');
        expect(options.body.get('requirementFile')).toBeNull();
        expect(options.body.get('repositoryPath')).toBe('cx-ai');
    });
});
