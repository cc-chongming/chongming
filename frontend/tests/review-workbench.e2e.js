import { expect, test } from '@playwright/test';

const reviewId = '11111111-1111-1111-1111-111111111111';
const topicId = '20000000-0000-0000-0000-000000000001';
const claimId = '30000000-0000-0000-0000-000000000001';
const evidenceId = '50000000-0000-0000-0000-000000000001';

test('renders a replayable debate workbench without executing report content', async ({ page }) => {
    await page.route('**/api/reviews/**', async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path.endsWith('/events')) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
        if (path === `/api/reviews/${reviewId}`) return json({ reviewId, attempt: 1, stage: 'DEBATE_ROUND_1', progress: 52, lastSequence: 3, reviewVersion: 4, occurredAt: '2026-07-16 15:00:10', gate: null });
        if (path.endsWith('/plans')) return json({ items: [], nextAfterSequence: null });
        if (path.endsWith('/debates')) return json([{ topicId, subjectKey: '登录幂等边界', claimIds: [claimId], status: 'CHALLENGED', currentRound: 1, resolution: null, closedAt: null, claims: [{ claimId, role: 'PRODUCT', subjectKey: '登录幂等边界', severity: 'P1', position: 'SUPPORT', statement: '需要一次性登录令牌。', reasonSummary: '防止重复提交。', status: 'SUBMITTED', evidenceIds: [evidenceId] }], turns: [], judgement: null }]);
        if (path.endsWith(`/evidence/${evidenceId}`)) return json({ evidenceId, repoRevision: 'demo', snapshotRelativePath: 'src/main/java/App.java', lineNumber: 12, excerpt: '<script>window.__xss = true</script>', excerptHash: 'a', fileHash: 'b', createdAt: '2026-07-16 15:00:00' });
        if (path.endsWith('/human-review-items') || path.endsWith('/human-gate-decisions') || path.endsWith('/notifications') || path.endsWith('/report/versions')) return json([]);
        if (path.endsWith('/report')) return json({ title: '报告' }, 404);
        return json({});
    });

    await page.goto(`/index.html#/reviews/${reviewId}`);
    await expect(page.getByRole('heading', { name: '辩论时间线' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '公开对话流' })).toBeVisible();
    await expect(page.getByText('需要一次性登录令牌。')).toBeVisible();
    await page.getByRole('button', { name: '查看证据' }).click();
    await expect(page.getByText('<script>window.__xss = true</script>')).toBeVisible();
    await expect(page.locator('pre script')).toHaveCount(0);
});

test('starts a pending review with its server command contract', async ({ page }) => {
    const calls = [];
    await page.route('**/api/reviews/**', async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path.endsWith('/events')) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
        if (path === `/api/reviews/${reviewId}/start`) {
            calls.push({ headers: route.request().headers(), body: route.request().postDataJSON() });
            return json({ reviewId, attemptNo: 1, version: 3, stage: 'PLANNING', replayed: false }, 202);
        }
        if (path === `/api/reviews/${reviewId}`) return json({ reviewId, attempt: 1, stage: 'PENDING', progress: 0, lastSequence: 0, reviewVersion: 0, occurredAt: null, gate: null });
        if (path.endsWith('/plans')) return json({ items: [], nextAfterSequence: null });
        if (path.endsWith('/debates')) return json([]);
        if (path.endsWith('/human-review-items') || path.endsWith('/human-gate-decisions') || path.endsWith('/notifications') || path.endsWith('/report/versions')) return json([]);
        if (path.endsWith('/report')) return json({ title: '报告' }, 404);
        return json({});
    });

    await page.goto(`/index.html#/reviews/${reviewId}`);
    await page.getByRole('button', { name: '开始评审' }).click();

    await expect.poll(() => calls.length).toBe(1);
    expect(calls[0].headers['idempotency-key']).toBeTruthy();
    expect(calls[0].body).toMatchObject({ expectedVersion: 0, userId: 'demo-reviewer' });
    await expect(page.getByText('启动命令已受理，正在等待服务端事件。')).toBeVisible();
});
