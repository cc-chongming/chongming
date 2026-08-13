import { expect, test } from '@playwright/test';

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

test('renders the platform shell with grouped navigation, breadcrumb and live counts', async ({ page }) => {
    await page.route('**/api/dashboard', async (route) => {
        await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
                pendingRequirementCount: 2,
                activeReviewCount: 3,
                requirementStatusCounts: { DRAFT: 2, PENDING_REVIEW: 2, REVIEWING: 3, APPROVED: 3, REJECTED: 1, DEVELOPING: 1, DONE: 0 },
                activeReviews: [],
                recentActivities: []
            })
        });
    });
    await page.route('**/api/repositories', (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ id: 'cx-ai', displayName: 'CX AI' }])
    }));
    await page.route('**/api/requirements**', (route) => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], total: 0, page: 1, size: 20 })
    }));
    await page.route('**/api/reviews**', (route) => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], total: 0, page: 1, size: 20 })
    }));
    await page.route('**/api/reports**', (route) => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], total: 0, page: 1, size: 20 })
    }));

    await page.goto('/index.html#/dashboard');

    await expect(page.locator('.logo-text .logo')).toHaveText('重明');
    await expect(page.locator('.logo-text .sub')).toHaveText('需求生命周期管理');
    await expect(page.locator('.nav-group-title')).toHaveText(['概览', '需求管理', '评审', '报告', '任务中心']);
    await expect(page.locator('.breadcrumb .cur')).toHaveText('工作台');

    const requirementBadge = page.locator('.nav-item', { hasText: '需求库' }).locator('.badge-count');
    await expect(requirementBadge).toHaveText('12');
    const reviewBadge = page.locator('.nav-item', { hasText: '评审列表' }).locator('.badge-count');
    await expect(reviewBadge).toHaveText('3');

    await page.locator('.nav-item', { hasText: '需求库' }).click();
    await expect(page.locator('.breadcrumb .cur')).toHaveText('需求库');
    await expect(page.locator('.nav-item.active', { hasText: '需求库' })).toBeVisible();
    await expect(page.locator('.nav-item.active', { hasText: '新建需求' })).toHaveCount(0);

    // 新建入口位于需求库页内（侧栏只保留需求库一个分组项），点击后需求库保持高亮。
    await page.getByRole('link', { name: /新建需求/ }).click();
    await page.getByRole('link', { name: '＋ 新建需求' }).click();
    await expect(page.locator('.breadcrumb .cur')).toHaveText('新建需求');
    await expect(page.locator('.nav-item.active', { hasText: '需求库' })).toBeVisible();
    await expect(page.locator('.nav-item.active', { hasText: '新建需求' })).toHaveCount(0);
    await expect(page.locator('.nav-item.active', { hasText: '需求库' })).toBeVisible();

    await page.locator('.nav-item', { hasText: '评审列表' }).click();
    await expect(page.locator('.breadcrumb .cur')).toHaveText('评审列表');
    await expect(page.locator('.nav-item.active', { hasText: '评审列表' })).toBeVisible();

    await page.locator('.breadcrumb .home').click();
    await expect(page.locator('.breadcrumb .cur')).toHaveText('工作台');
    await expect(page.locator('.nav-item.active', { hasText: '工作台' })).toBeVisible();
});
