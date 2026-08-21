import { expect, test } from '@playwright/test';

// Task-center e2e: ADMIN session seeded via a long-lived local JWT so the router
// guard stays green; every /api call is mocked with page.route (no real backend).
function makeJwt(payload) {
    const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
    return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(payload)}.e2e-signature`;
}

const adminUser = { username: 'admin-user', displayName: '管理员', role: 'ADMIN' };
const adminToken = makeJwt({ sub: adminUser.username, exp: 4102444800 });

const taskFixtures = {
    pendingAssign: {
        taskId: 'task-pending-assign', title: '实现评审任务派发', requirementId: 'req-1',
        requirementTitle: '任务流转需求', status: 'PENDING_ASSIGN', assigneeUsername: null,
        dispatcherUsername: null, updatedAt: '2026-08-12 09:00', createdAt: '2026-08-11 09:00',
        acceptanceNote: null, reviewId: null, version: 3
    },
    developing: {
        taskId: 'task-developing', title: '开发验收提交能力', requirementId: 'req-1',
        requirementTitle: '任务流转需求', status: 'DEVELOPING', assigneeUsername: 'admin-user',
        dispatcherUsername: 'admin-user', updatedAt: '2026-08-12 10:00', createdAt: '2026-08-11 09:00',
        acceptanceNote: null, reviewId: null, version: 5
    },
    pendingAcceptance: {
        taskId: 'task-pending-acceptance', title: '报告能力验收', requirementId: 'req-2',
        requirementTitle: '报告需求', status: 'PENDING_ACCEPTANCE', assigneeUsername: 'dev-li',
        dispatcherUsername: 'admin-user', updatedAt: '2026-08-12 11:00', createdAt: '2026-08-10 09:00',
        acceptanceNote: null, reviewId: 'review-9', version: 7
    },
    done: {
        taskId: 'task-done', title: '已完成的历史任务', requirementId: null,
        requirementTitle: null, status: 'DONE', assigneeUsername: 'dev-li',
        dispatcherUsername: 'admin-user', updatedAt: '2026-08-09 15:00', createdAt: '2026-08-01 09:00',
        acceptanceNote: '验收通过。', reviewId: null, version: 9
    }
};

const userList = [
    { username: 'dev-li', displayName: '李开发', role: 'DEVELOPER' },
    { username: 'dev-zhang', displayName: '张开发', role: 'DEVELOPER' }
];

function json(body) {
    return { status: 200, contentType: 'application/json', body: JSON.stringify(body) };
}

test.beforeEach(async ({ page }) => {
    await page.addInitScript(({ token, user }) => {
        localStorage.setItem('chongming-auth', JSON.stringify({ token, user }));
    }, { token: adminToken, user: adminUser });
    // 兜底拦截：具体路由未覆盖的 /api 请求不得经 vite 代理泄漏到本地后端（其 401 会清除会话并跳登录页）。
    // pathname 谓词避免误拦 Vite 模块请求。
    await page.route((url) => url.pathname.startsWith('/api/'), (route) => route.fulfill({
        status: 404, contentType: 'application/json', body: JSON.stringify({ detail: 'not mocked' })
    }));
    await page.route((url) => url.pathname === '/api/dashboard', (route) => route.fulfill(json({
        pendingRequirementCount: 0,
        activeReviewCount: 0,
        requirementStatusCounts: { DRAFT: 0, PENDING_REVIEW: 0, REVIEWING: 0, APPROVED: 0, REJECTED: 0, DEVELOPING: 0, DONE: 0 },
        activeReviews: [],
        recentActivities: []
    })));
});

/** Mocks GET /api/tasks and records the latest query string for assertions. */
async function mockTaskList(page, state) {
    await page.route((url) => url.pathname === '/api/tasks', (route) => {
        state.lastListQuery = Object.fromEntries(new URL(route.request().url()).searchParams.entries());
        route.fulfill(json({
            items: [taskFixtures.pendingAssign, taskFixtures.developing, taskFixtures.pendingAcceptance, taskFixtures.done],
            page: 1, size: 20, total: 4
        }));
    });
}

test('renders the task list with status tags, ADMIN assign action and the task-center nav group', async ({ page }) => {
    const state = {};
    await mockTaskList(page, state);

    await page.goto('/index.html#/tasks');

    await expect(page.locator('.breadcrumb .cur')).toHaveText('全部任务');
    await expect(page.locator('.platform-page h1')).toHaveText('全部任务');
    await expect(page.locator('.nav-group-title')).toHaveText(['概览', '需求管理', '评审', '报告', '任务中心']);
    await expect(page.locator('.nav-group', { hasText: '任务中心' }).locator('.nav-label'))
        .toHaveText(['全部任务', '我的任务']);

    const rows = page.locator('.task-row');
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(0).locator('.tag')).toHaveText('待指派');
    await expect(rows.nth(0).locator('.tag')).toHaveClass(/tag-pending/);
    await expect(rows.nth(1).locator('.tag')).toHaveText('开发中');
    await expect(rows.nth(2).locator('.tag')).toHaveText('待验收');
    await expect(rows.nth(3).locator('.tag')).toHaveText('已完成');

    // 只有 PENDING_ASSIGN 行向 ADMIN 暴露指派操作（exact 避免命中“待指派”筛选按钮）。
    await expect(page.getByRole('button', { name: '指派', exact: true })).toHaveCount(1);
    await expect(rows.nth(1).getByRole('button', { name: '指派', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '查看我的任务' })).toBeVisible();
});

test('the my-tasks entry loads the list with the mine flag', async ({ page }) => {
    const state = {};
    await mockTaskList(page, state);

    await page.goto('/index.html#/tasks/mine');

    await expect(page.locator('.breadcrumb .cur')).toHaveText('我的任务');
    await expect(page.locator('.platform-page h1')).toHaveText('我的任务');
    await expect(page.locator('.task-row')).toHaveCount(4);
    expect(state.lastListQuery).toMatchObject({ mine: 'true', page: '1' });
    await expect(page.getByRole('link', { name: '查看全部任务' })).toBeVisible();
});

test('ADMIN assigns a pending task to a selected user with the current version', async ({ page }) => {
    const state = {};
    await mockTaskList(page, state);
    let assignBody = null;
    await page.route((url) => url.pathname === '/api/users', (route) => route.fulfill(json(userList)));
    await page.route((url) => url.pathname === '/api/tasks/task-pending-assign/assign', (route) => {
        assignBody = route.request().postDataJSON();
        route.fulfill(json({ ...taskFixtures.pendingAssign, status: 'DEVELOPING', assigneeUsername: 'dev-li', version: 4 }));
    });

    await page.goto('/index.html#/tasks');
    await page.getByRole('button', { name: '指派', exact: true }).click();

    await expect(page.locator('#task-assign-title')).toHaveText('指派任务');
    const assigneeSelect = page.locator('.panel select');
    await expect(assigneeSelect).toBeVisible();
    await expect(assigneeSelect.locator('option')).toHaveCount(3); // 占位 + 2 名用户
    await assigneeSelect.selectOption('dev-li');

    const assignRequest = page.waitForRequest(
        (request) => request.url().includes('/api/tasks/task-pending-assign/assign') && request.method() === 'POST'
    );
    await page.getByRole('button', { name: '确认指派' }).click();
    await assignRequest;

    expect(assignBody).toEqual({ assigneeUsername: 'dev-li', expectedVersion: 3 });
    await expect(page.locator('#task-assign-title')).toHaveCount(0); // 指派成功后收下面板并刷新列表
});

test('the assignee submits acceptance from a developing task with the current version', async ({ page }) => {
    await page.route((url) => url.pathname === '/api/tasks/task-developing', (route) => route.fulfill(json(taskFixtures.developing)));
    let submitBody = null;
    await page.route((url) => url.pathname === '/api/tasks/task-developing/submit-acceptance', (route) => {
        submitBody = route.request().postDataJSON();
        route.fulfill(json({ ...taskFixtures.developing, status: 'PENDING_ACCEPTANCE', version: 6 }));
    });

    await page.goto('/index.html#/tasks/task-developing');

    await expect(page.locator('.rd-info h1')).toHaveText('开发验收提交能力');
    await expect(page.locator('.rd-info .tag')).toHaveText('开发中');
    await page.getByRole('button', { name: '提交验收' }).click();

    expect(submitBody).toEqual({ expectedVersion: 5 });
    await expect(page.locator('.rd-info .tag')).toHaveText('待验收');
    // 当前会话为 ADMIN，任务进入待验收后操作区切换为验收表单。
    await expect(page.getByRole('button', { name: '验收通过' })).toBeVisible();
});

test('ADMIN accepts a pending-acceptance task with a note and the current version', async ({ page }) => {
    await page.route((url) => url.pathname === '/api/tasks/task-pending-acceptance', (route) => route.fulfill(json(taskFixtures.pendingAcceptance)));
    let acceptBody = null;
    await page.route((url) => url.pathname === '/api/tasks/task-pending-acceptance/accept', (route) => {
        acceptBody = route.request().postDataJSON();
        route.fulfill(json({ ...taskFixtures.pendingAcceptance, status: 'DONE', acceptanceNote: '验收通过，功能符合预期', version: 8 }));
    });

    await page.goto('/index.html#/tasks/task-pending-acceptance');

    await expect(page.locator('.rd-info .tag')).toHaveText('待验收');
    await page.locator('.review-form textarea').fill('验收通过，功能符合预期');
    await page.getByRole('button', { name: '验收通过' }).click();

    expect(acceptBody).toEqual({ note: '验收通过，功能符合预期', expectedVersion: 7 });
    await expect(page.locator('.rd-info .tag')).toHaveText('已完成');
    await expect(page.getByText('验收通过，功能符合预期')).toBeVisible();
});

test('ADMIN rejects a pending-acceptance task back to development with a note', async ({ page }) => {
    await page.route((url) => url.pathname === '/api/tasks/task-pending-acceptance', (route) => route.fulfill(json(taskFixtures.pendingAcceptance)));
    let rejectBody = null;
    await page.route((url) => url.pathname === '/api/tasks/task-pending-acceptance/reject', (route) => {
        rejectBody = route.request().postDataJSON();
        route.fulfill(json({ ...taskFixtures.pendingAcceptance, status: 'DEVELOPING', version: 8 }));
    });

    await page.goto('/index.html#/tasks/task-pending-acceptance');

    await expect(page.locator('.rd-info .tag')).toHaveText('待验收');
    await page.locator('.review-form textarea').fill('报告导出仍有缺陷，请修复后重新提验');
    await page.getByRole('button', { name: '打回修改' }).click();

    expect(rejectBody).toEqual({ note: '报告导出仍有缺陷，请修复后重新提验', expectedVersion: 7 });
    await expect(page.locator('.rd-info .tag')).toHaveText('开发中');
});

// [AIREVIEW-PLAN-031#2] Handoff reads the non-admin directory and the detail page carries a
// delivery-attachment panel shared by handoff and acceptance.
test('holder picks a non-admin handoff target and shares delivery attachments', async ({ page }) => {
    const devUser = { username: 'dev-li', displayName: '李开发', role: 'DEVELOPER' };
    await page.addInitScript(({ token, user }) => {
        localStorage.setItem('chongming-auth', JSON.stringify({ token, user }));
    }, { token: makeJwt({ sub: devUser.username, exp: 4102444800 }), user: devUser });

    const attachmentTask = {
        taskId: 'task-attachments', title: '附件流转任务', requirementId: 'req-1',
        requirementTitle: '任务流转需求', status: 'DEVELOPING', assigneeUsername: 'dev-li',
        currentHolderUsername: 'dev-li', dispatcherUsername: 'admin-user',
        updatedAt: '2026-08-20 10:00', createdAt: '2026-08-19 09:00',
        acceptanceNote: null, reviewId: null, handoffHistory: [], version: 2
    };
    let attachments = [{
        attachmentId: 'att-1', taskId: 'task-attachments', fileName: 'design.md',
        contentType: 'text/markdown', fileSize: 1024, uploadedBy: 'dev-li',
        createdAt: '2026-08-20T02:00:00Z'
    }];
    await page.route((url) => url.pathname === '/api/tasks/task-attachments', (route) => route.fulfill(json(attachmentTask)));
    await page.route((url) => url.pathname === '/api/tasks/assignable-users', (route) => route.fulfill(json([
        { username: 'dev-li', displayName: '李开发' },
        { username: 'dev-zhang', displayName: '张开发' }
    ])));
    await page.route((url) => url.pathname === '/api/tasks/task-attachments/attachments', (route) => {
        if (route.request().method() === 'POST') {
            const entry = {
                attachmentId: 'att-2', taskId: 'task-attachments', fileName: 'self-test.md',
                contentType: 'text/markdown', fileSize: 128, uploadedBy: 'dev-li',
                createdAt: '2026-08-20T03:00:00Z'
            };
            attachments = [...attachments, entry];
            return route.fulfill(json(entry));
        }
        return route.fulfill(json(attachments));
    });
    await page.route((url) => url.pathname === '/api/tasks/task-attachments/attachments/att-1', (route) => route.fulfill({
        status: 200,
        contentType: 'text/markdown',
        headers: { 'Content-Disposition': "attachment; filename*=UTF-8''design.md" },
        body: '# 设计文档'
    }));

    await page.goto('/index.html#/tasks/task-attachments');

    // 流转下拉只读非管理员用户目录，且排除当前持有人。
    const handoffSelect = page.locator('.review-form select');
    await expect(handoffSelect).toBeVisible();
    await expect(handoffSelect.locator('option')).toHaveCount(2); // 占位 + 张开发
    await expect(handoffSelect.locator('option', { hasText: 'dev-li' })).toHaveCount(0);
    await handoffSelect.selectOption('dev-zhang');

    // 附件面板展示已上传交付物，可下载。
    await expect(page.getByText('design.md')).toBeVisible();
    const downloadRequest = page.waitForRequest(
        (request) => request.url().includes('/api/tasks/task-attachments/attachments/att-1'));
    await page.getByRole('link', { name: 'design.md' }).click();
    await downloadRequest;

    // 持有人上传新附件后列表即时刷新。
    await page.setInputFiles('.review-form input[type="file"]', {
        name: 'self-test.md', mimeType: 'text/markdown', buffer: Buffer.from('# 自测报告')
    });
    await page.getByRole('button', { name: '上传', exact: true }).click();
    await expect(page.getByText('self-test.md')).toBeVisible();
});
