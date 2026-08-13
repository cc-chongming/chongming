import { expect, test } from '@playwright/test';

// [AIREVIEW-PLAN-023#2] Repository fixtures expose identifiers and display names only.
// [AIREVIEW-PLAN-023#3] Draft launch is exercised through the single idempotent command endpoint.

const reviewId = '11111111-1111-1111-1111-111111111111';
const topicId = '20000000-0000-0000-0000-000000000001';
const claimId = '30000000-0000-0000-0000-000000000001';
const evidenceId = '50000000-0000-0000-0000-000000000001';

// Auth guard seeded: e2e flows exercise protected routes with a long-lived local JWT.
test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
        const payload = btoa(JSON.stringify({ sub: 'e2e-user', exp: 4102444800 }))
            .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
        localStorage.setItem('chongming-auth', JSON.stringify({
            token: `e2e.${payload}.signature`,
            user: { username: 'e2e-user', displayName: 'E2E 用户', role: 'PRODUCT' }
        }));
    });
    // 兜底拦截：具体路由未覆盖的 /api 请求不得经 vite 代理泄漏到本地后端（其 401 会清除会话并跳登录页）。
    await page.route((url) => url.pathname.startsWith('/api/'), (route) => route.fulfill({
        status: 404, contentType: 'application/json', body: JSON.stringify({ detail: 'not mocked' })
    }));
});

test.beforeEach(async ({ page }) => {
    await page.route('**/api/dashboard', (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            pendingRequirementCount: 0,
            activeReviewCount: 0,
            requirementStatusCounts: {},
            activeReviews: [],
            recentActivities: []
        })
    }));
    await page.route('**/api/repositories', (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
    }));
});

test('renders a replayable debate workbench without executing report content', async ({ page }) => {
    await page.route('**/api/reviews/**', async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path.endsWith('/events')) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
        if (path === `/api/reviews/${reviewId}`) return json({ reviewId, attempt: 1, stage: 'DEBATE_ROUND_1', progress: 52, lastSequence: 3, reviewVersion: 4, occurredAt: '2026-07-16 15:00:10', gate: null });
        if (path.endsWith('/plans')) return json({ items: [], nextAfterSequence: null });
        if (path.endsWith('/debates')) return json([{ topicId, subjectKey: '登录幂等边界', claimIds: [claimId], status: 'CHALLENGED', currentRound: 1, resolution: null, closedAt: null, claims: [{ claimId, role: 'PRODUCT', subjectKey: '登录幂等边界', severity: 'P1', position: 'SUPPORT', statement: '需要一次性登录令牌。', reasonSummary: '防止重复提交。', status: 'SUBMITTED', evidenceIds: [evidenceId] }], turns: [], judgement: null }]);
        if (path.endsWith('/claims')) return json([]);
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
        if (path.endsWith('/debates') || path.endsWith('/claims')) return json([]);
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

test('renders the live review page as the full-flow workspace with phase-specific views', async ({ page }) => {
    const debateTopicId = '20000000-0000-0000-0000-000000000002';
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
                occurredAt: '2026-08-04 15:25:57', gate: { result: 'PASS', status: 'DRAFT', reasonSummary: 'AI 判断可以通过。' },
                activatedRoles: [
                    { role: 'PRODUCT', agentLabel: 'product-reviewer', initialReviewCompleted: false },
                    { role: 'TESTING', agentLabel: 'testing-reviewer', initialReviewCompleted: false }
                ]
            });
        }
        if (path.endsWith('/plans')) return json({ items: [], nextAfterSequence: null });
        if (path.endsWith('/debates')) {
            return json([{
                topicId: debateTopicId, subjectKey: '数据冲突解决策略', claimIds: [claimId], status: 'CHALLENGED',
                currentRound: 1, resolution: null, closedAt: null,
                claims: [
                    { claimId, role: 'PRODUCT', subjectKey: '增量同步是核心诉求', severity: 'P1', position: 'SUPPORT', statement: '增量同步必须上线。', reasonSummary: '全量方案已到瓶颈。', status: 'SUBMITTED', evidenceIds: [] },
                    { claimId: '30000000-0000-0000-0000-000000000002', role: 'BACKEND', subjectKey: '冲突策略未定义', severity: 'P0', position: 'OPPOSE', statement: '并发写入一致性无法保证。', reasonSummary: '缺少版本号字段。', status: 'SUBMITTED', evidenceIds: [] },
                    { claimId: '30000000-0000-0000-0000-000000000003', role: 'SECURITY', subjectKey: '认证边界待确认', severity: 'P2', position: 'NEUTRAL', statement: '认证改动范围尚待确认。', reasonSummary: '当前证据不足。', status: 'SUBMITTED', evidenceIds: [] }
                ],
                turns: [{ turnId: '40000000-0000-0000-0000-000000000001', round: 1, actorRole: 'BACKEND', targetRole: 'PRODUCT', type: 'CHALLENGE', targetClaimId: claimId, targetTurnId: null, content: '业务上能接受数据丢失吗？', evidenceIds: [], stanceBefore: 'OPPOSE', stanceAfter: null }],
                judgement: null
            }]);
        }
        if (path.endsWith('/claims')) {
            return json([
                { claimId, role: 'PRODUCT', subjectKey: '增量同步是核心诉求', severity: 'P1', position: 'SUPPORT', statement: '增量同步必须上线。', reasonSummary: '全量方案已到瓶颈。', status: 'SUBMITTED', evidenceIds: [] },
                { claimId: '30000000-0000-0000-0000-000000000002', role: 'BACKEND', subjectKey: '冲突策略未定义', severity: 'P0', position: 'OPPOSE', statement: '并发写入一致性无法保证。', reasonSummary: '缺少版本号字段。', status: 'SUBMITTED', evidenceIds: [] }
            ]);
        }
        if (path.endsWith('/human-gate-decisions')) return json([{ gateVersion: 1, result: 'RETURN', reason: '范围需要补齐', decidedAt: '2026-08-04 15:30:00' }]);
        if (path.endsWith('/human-review-items')
            || path.endsWith('/notifications') || path.endsWith('/report/versions')) return json([]);
        if (path.endsWith('/report')) return json({ title: '报告' }, 404);
        return json({});
    });

    await page.goto(`/index.html#/reviews/${reviewId}/live`);

    await expect(page.getByText('需求评审全流程', { exact: true })).toBeVisible();
    await expect(page.getByRole('navigation', { name: '评审流程' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '运行调试' })).toBeVisible();
    await expect(page.getByRole('button', { name: '收起观察' })).toBeVisible();

    // 默认停留在当前阶段：只展示实际激活或有正式 Claim 的动态角色。
    await expect(page.getByRole('heading', { name: /独立审查/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /产品经理/ })).toBeVisible();
    await expect(page.getByText('发现 1 项：0 项阻断、1 项高风险、0 项改进建议')).toBeVisible();
    // 已激活但尚无 Claim 与运行事件的角色卡展示等待文案。
    await expect(page.getByText('角色已激活，等待公开运行事件。')).toBeVisible();
    await expect(page.getByRole('button', { name: /项目经理/ })).toHaveCount(0);
    // 展开角色卡后可看到论点与可折叠的运行详情
    await page.getByRole('button', { name: /后端工程师/ }).click();
    await expect(page.getByText('并发写入一致性无法保证。')).toBeVisible();

    // 切换到冲突检测：展示 SUPPORT vs OPPOSE 推导出的冲突卡
    await page.getByRole('button', { name: /冲突检测/ }).click();
    await expect(page.getByText('冲突 #1 [P0] · 数据冲突解决策略')).toBeVisible();
    await expect(page.getByText('增量同步必须上线。')).toBeVisible();

    // 切换到多轮辩论：展示回合 Tabs、支持/质疑对局与对话流
    await page.getByRole('button', { name: /多轮辩论/ }).click();
    await expect(page.getByRole('heading', { name: /多轮辩论 — 数据冲突解决策略/ })).toBeVisible();
    await expect(page.getByText('🟢 支持方')).toBeVisible();
    await expect(page.getByText('🔴 质疑方')).toBeVisible();
    await expect(page.getByText('⚪ 中立方')).toBeVisible();
    await expect(page.getByText('认证改动范围尚待确认。')).toBeVisible();
    await expect(page.getByText('业务上能接受数据丢失吗？')).toBeVisible();
    await expect(page.getByText('共识度（支持 Claim 占比）')).toBeVisible();

    // 切换到人工决策：展示 Gate 草案区与决策按钮条
    await page.getByRole('button', { name: /人工决策/ }).click();
    await expect(page.getByText('系统已暂停 AI 输出，最终结论必须由人工在工作台明确选择并提交')).toBeVisible();
    await expect(page.getByText('人工结论与 AI Gate 草案不同')).toBeVisible();
    // 结论文案会在结论链、人工理由与 Gate 版本历史多处展示。
    await expect(page.getByText('范围需要补齐').first()).toBeVisible();
    await expect(page.getByLabel('评审结论链').getByText('范围需要补齐', { exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '进入人工决策' })).toBeVisible();
});

test('removes the temporary draft when the uploaded Markdown already has a review', async ({ page }) => {
    const requirementId = '70000000-0000-0000-0000-000000000001';
    const reusedReviewId = '80000000-0000-0000-0000-000000000001';
    const deletedDrafts = [];
    await page.route(/\/api\/(?:repositories|requirements|reviews)(?:\/|\?|$)/, async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const method = route.request().method();
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path === '/api/repositories' && method === 'GET') {
            return json([{ id: 'cx-ai', displayName: 'CX AI' }]);
        }
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
    await page.getByPlaceholder('简短描述需求内容').fill('重复快照测试');
    await expect(page.getByLabel('目标仓库')).toHaveValue('cx-ai');
    await page.locator('input[type="file"][accept*=".md"]').setInputFiles({
        name: 'repeat.md', mimeType: 'text/markdown', buffer: Buffer.from('# 已有评审的需求')
    });

    await page.getByRole('button', { name: /提交并启动评审/ }).click();

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
    await page.route(/\/api\/(?:repositories|requirements)(?:\/|\?|$)/, async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const method = route.request().method();
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path === '/api/repositories' && method === 'GET') {
            return json([{ id: 'cx-ai', displayName: 'CX AI' }]);
        }
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

test('launches an unbound draft from its detail page with a configured repository', async ({ page }) => {
    const requirementId = '91000000-0000-0000-0000-000000000001';
    const launchedReviewId = '92000000-0000-0000-0000-000000000001';
    const draft = {
        id: requirementId, title: '待发起评审的草稿', description: '草稿描述', status: 'DRAFT', creatorId: 'demo-reviewer',
        assigneeId: null, repositoryPath: 'legacy-repository', priority: 'P1', reviewId: null, version: 3,
        createdAt: '2026-08-10T00:00:00Z', updatedAt: '2026-08-10T00:00:00Z'
    };
    const launchRequests = [];
    await page.route(/\/api\/(?:dashboard|repositories|requirements|reviews)(?:\/|\?|$)/, async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const method = route.request().method();
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path === '/api/repositories') {
            return json([{ id: 'cx-ai', displayName: 'CX AI' }, { id: 'chongming', displayName: '重明' }]);
        }
        if (path === `/api/requirements/${requirementId}` && method === 'GET') return json(draft);
        if (path === `/api/requirements/${requirementId}/reviews` && method === 'POST') {
            launchRequests.push({
                headers: route.request().headers(),
                body: route.request().postData() ?? ''
            });
            if (launchRequests.length === 1) {
                return json({
                    detail: 'review was bound but could not be started; retry the same launch command',
                    code: 'REVIEW_START_FAILED', phase: 'BOUND', recoverable: true, existingReviewId: launchedReviewId
                }, 409);
            }
            return json({
                requirementId,
                reviewId: launchedReviewId,
                stage: 'PLANNING',
                phase: 'STARTED',
                recoverable: false,
                liveUrl: `/reviews/${launchedReviewId}/live`
            }, 202);
        }
        if (path.endsWith('/events') || path.endsWith('/runtime/ag-ui')) {
            return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
        }
        if (path === `/api/reviews/${launchedReviewId}`) {
            return json({ reviewId: launchedReviewId, attempt: 1, stage: 'PLANNING', progress: 10, reviewVersion: 1 });
        }
        if (path.endsWith('/plans')) return json({ items: [], nextAfterSequence: null });
        if (path.endsWith('/debates') || path.endsWith('/claims') || path.endsWith('/human-review-items')
            || path.endsWith('/human-gate-decisions') || path.endsWith('/notifications') || path.endsWith('/report/versions')) return json([]);
        if (path.endsWith('/report')) return json({ detail: 'Not found' }, 404);
        return json({});
    });

    await page.goto(`/index.html#/requirements/${requirementId}`);
    await page.getByRole('button', { name: '发起评审' }).click();

    await expect(page.getByText('历史仓库“legacy-repository”已不可用，请重新选择。')).toBeVisible();
    await page.getByLabel('目标仓库').selectOption('cx-ai');
    await page.getByLabel('评审需求文档（.md）').setInputFiles({
        name: 'draft.md', mimeType: 'text/markdown', buffer: Buffer.from('# 草稿评审')
    });
    await page.getByRole('button', { name: '确认发起评审' }).click();

    await expect(page.getByText(/已绑定评审，可使用同一命令安全重试/)).toBeVisible();
    await expect(page.getByRole('link', { name: '查看已绑定评审' })).toHaveAttribute('href', `#/reviews/${launchedReviewId}/live`);
    await page.getByRole('button', { name: '确认发起评审' }).click();

    await expect(page).toHaveURL(new RegExp(`#\/reviews\/${launchedReviewId}\/live$`));
    expect(launchRequests).toHaveLength(2);
    expect(launchRequests[0].headers['idempotency-key']).toBeTruthy();
    expect(launchRequests[1].headers['idempotency-key']).toBe(launchRequests[0].headers['idempotency-key']);
    expect(launchRequests[0].body).toContain('name="repositoryPath"');
    expect(launchRequests[0].body).toContain('cx-ai');
    expect(launchRequests[0].body).toContain('name="expectedVersion"');
    expect(launchRequests[0].body).toContain('3');
    expect(launchRequests[0].body).toContain('name="publicTasks"');
});

test('refreshes a draft and rotates the command key after a version conflict', async ({ page }) => {
    const requirementId = '93000000-0000-0000-0000-000000000001';
    let reads = 0;
    const launches = [];
    await page.route(/\/api\/(?:dashboard|repositories|requirements|reviews)(?:\/|\?|$)/, async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const method = route.request().method();
        const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path === '/api/repositories') return json([{ id: 'cx-ai', displayName: 'CX AI' }]);
        if (path === `/api/requirements/${requirementId}` && method === 'GET') {
            reads += 1;
            return json({
                id: requirementId, title: '并发更新草稿', description: '', status: 'DRAFT', repositoryPath: 'cx-ai',
                priority: 'P1', reviewId: null, version: reads === 1 ? 3 : 4
            });
        }
        if (path === `/api/requirements/${requirementId}/reviews` && method === 'POST') {
            launches.push({ headers: route.request().headers(), body: route.request().postData() ?? '' });
            return json({ detail: 'expectedVersion does not match aggregate version', code: 'VERSION_CONFLICT' }, 409);
        }
        return json([]);
    });

    await page.goto(`/index.html#/requirements/${requirementId}`);
    await page.getByRole('button', { name: '发起评审' }).click();
    await page.getByLabel('评审需求文档（.md）').setInputFiles({
        name: 'version.md', mimeType: 'text/markdown', buffer: Buffer.from('# 版本冲突')
    });
    await page.getByRole('button', { name: '确认发起评审' }).click();

    await expect(page.getByText('需求版本已刷新，请检查最新草稿并确认后重试。')).toBeVisible();
    expect(reads).toBeGreaterThanOrEqual(2);
    // 版本冲突后页面会重新加载详情，发起面板重建后需重新选择文档再重试。
    await page.getByLabel('评审需求文档（.md）').setInputFiles({
        name: 'version-retry.md', mimeType: 'text/markdown', buffer: Buffer.from('# 版本冲突重试')
    });
    await page.getByRole('button', { name: '确认发起评审' }).click();
    await expect.poll(() => launches.length).toBe(2);
    expect(launches[1].headers['idempotency-key']).not.toBe(launches[0].headers['idempotency-key']);
    expect(launches[1].body).toContain('name="expectedVersion"');
    expect(launches[1].body).toContain('4');
});

test('uses configured repository options on the independent review form', async ({ page }) => {
    await page.route('**/api/repositories', (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ id: 'cx-ai', displayName: 'CX AI' }])
    }));

    await page.goto('/index.html#/create');

    await expect(page.getByLabel('目标仓库')).toHaveValue('cx-ai');
    await expect(page.getByText('CX AI（cx-ai）')).toBeAttached();
});

test('disables requirement creation when the server has no configured repositories', async ({ page }) => {
    await page.route('**/api/repositories', (route) => route.fulfill({
        status: 200, contentType: 'application/json', body: '[]'
    }));

    await page.goto('/index.html#/requirements/create');

    await expect(page.getByRole('button', { name: '保存草稿' })).toBeDisabled();
    await expect(page.getByRole('button', { name: /提交并启动评审/ })).toBeDisabled();
    await expect(page.getByText('当前没有可用仓库，暂不能保存或提交需求。')).toBeVisible();
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
