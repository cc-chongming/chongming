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

test('renders the live review page as the full-flow workspace', async ({ page }) => {
    await page.route('**/api/reviews/**', async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path.endsWith('/events') || path.endsWith('/runtime/ag-ui')) {
            return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
        }
        if (path === `/api/reviews/${reviewId}`) {
            return json({
                reviewId, attempt: 1, stage: 'INITIAL_REVIEW', progress: 40, lastSequence: 2, reviewVersion: 4,
                occurredAt: '2026-08-04 15:25:57', gate: null,
                activatedRoles: [{ role: 'PRODUCT', agentLabel: 'product-reviewer', initialReviewCompleted: false }]
            });
        }
        if (path.endsWith('/plans')) return json({ items: [], nextAfterSequence: null });
        if (path.endsWith('/debates') || path.endsWith('/human-review-items') || path.endsWith('/human-gate-decisions')
            || path.endsWith('/notifications') || path.endsWith('/report/versions')) return json([]);
        if (path.endsWith('/report')) return json({ title: '报告' }, 404);
        return json({});
    });

    await page.goto(`/index.html#/reviews/${reviewId}/live`);

    await expect(page.getByText('需求评审全流程', { exact: true })).toBeVisible();
    await expect(page.getByRole('navigation', { name: '评审流程' })).toBeVisible();
    await expect(page.getByText('Director 协调者', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: '评审席位' })).toBeVisible();
    await expect(page.getByRole('button', { name: '运行调试' })).toBeVisible();
    await expect(page.getByText('参数与完整结果仅在展开后可见。')).toBeVisible();
});

test('removes the temporary draft when the uploaded Markdown already has a review', async ({ page }) => {
    const requirementId = '70000000-0000-0000-0000-000000000001';
    const reusedReviewId = '80000000-0000-0000-0000-000000000001';
    const deletedDrafts = [];
    await page.route(/\/api\/(?:requirements|reviews)(?:\/|\?|$)/, async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const method = route.request().method();
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path === '/api/requirements' && method === 'POST') {
            return json({ id: requirementId, version: 0, status: 'DRAFT' }, 201);
        }
        if (path === '/api/reviews' && method === 'POST') {
            return json({ reviewId: reusedReviewId, attempt: 1, reused: true }, 202);
        }
        if (path === `/api/requirements/${requirementId}` && method === 'DELETE') {
            deletedDrafts.push(requestUrl.searchParams.get('expectedVersion'));
            return route.fulfill({ status: 204 });
        }
        return json({ detail: 'Unexpected request' }, 404);
    });

    await page.goto('/index.html#/requirements/create');
    await page.getByLabel('需求标题').fill('重复快照测试');
    await page.getByLabel('仓库标识').fill('cx-ai');
    await page.getByLabel('评审需求文档（.md）').setInputFiles({
        name: 'repeat.md', mimeType: 'text/markdown', buffer: Buffer.from('# 已有评审的需求')
    });

    await page.getByRole('button', { name: '创建需求并启动评审' }).click();

    await expect(page.getByText('该 Markdown 快照已有评审，本次未保留重复需求草稿。可直接进入既有评审查看或重试。')).toBeVisible();
    await expect(page.getByRole('link', { name: '查看既有评审' })).toBeVisible();
    expect(deletedDrafts).toEqual(['0']);
});

test('edits and deletes an unbound requirement draft from its detail page', async ({ page }) => {
    const requirementId = '90000000-0000-0000-0000-000000000001';
    const draft = {
        id: requirementId, title: '可编辑草稿', description: '初始描述', status: 'DRAFT', creatorId: 'demo-reviewer',
        assigneeId: null, repositoryPath: 'cx-ai', priority: 'P1', reviewId: null, version: 0,
        createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:00:00Z'
    };
    let revisedPayload = null;
    let deletedVersion = null;
    await page.route(/\/api\/requirements(?:\/|\?|$)/, async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const method = route.request().method();
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path === `/api/requirements/${requirementId}` && method === 'GET') return json(draft);
        if (path === `/api/requirements/${requirementId}` && method === 'PUT') {
            revisedPayload = route.request().postDataJSON();
            return json({ ...draft, ...revisedPayload, version: 1 });
        }
        if (path === `/api/requirements/${requirementId}` && method === 'DELETE') {
            deletedVersion = requestUrl.searchParams.get('expectedVersion');
            return route.fulfill({ status: 204 });
        }
        if (path === '/api/requirements' && method === 'GET') return json({ items: [], page: 1, size: 20, total: 0 });
        return json({ detail: 'Unexpected request' }, 404);
    });

    await page.goto(`/index.html#/requirements/${requirementId}`);
    await page.getByRole('button', { name: '编辑需求' }).click();
    await page.getByLabel('需求描述').fill('已修改描述');
    await page.getByRole('button', { name: '保存修改' }).click();

    expect(revisedPayload).toMatchObject({ description: '已修改描述', expectedVersion: 0 });
    await expect(page.getByText('已修改描述')).toBeVisible();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: '删除需求' }).click();

    await expect(page).toHaveURL(/#\/requirements$/);
    expect(deletedVersion).toBe('1');
});

test('deletes a requirement directly from the requirement list regardless of its lifecycle status', async ({ page }) => {
    const requirementId = 'a0000000-0000-0000-0000-000000000001';
    const item = {
        id: requirementId, title: '已取消的测试需求', description: '', status: 'CANCELLED', creatorId: 'demo-reviewer',
        assigneeId: null, repositoryPath: 'cx-ai', priority: 'P1', reviewId: reviewId, version: 4,
        createdAt: '2026-08-04T00:00:00Z', updatedAt: '2026-08-04T00:00:00Z'
    };
    let deletedVersion = null;
    let listReads = 0;
    await page.route(/\/api\/requirements(?:\/|\?|$)/, async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const method = route.request().method();
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path === '/api/requirements' && method === 'GET') {
            listReads += 1;
            return json({ items: listReads === 1 ? [item] : [], page: 1, size: 20, total: listReads === 1 ? 1 : 0 });
        }
        if (path === `/api/requirements/${requirementId}` && method === 'DELETE') {
            deletedVersion = requestUrl.searchParams.get('expectedVersion');
            return route.fulfill({ status: 204 });
        }
        return json({ detail: 'Unexpected request' }, 404);
    });

    await page.goto('/index.html#/requirements');
    await expect(page.getByText('已取消的测试需求')).toBeVisible();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: '删除' }).click();

    await expect(page.getByText('没有符合条件的需求。')).toBeVisible();
    expect(deletedVersion).toBe('4');
});
