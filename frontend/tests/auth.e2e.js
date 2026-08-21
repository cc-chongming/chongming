import { expect, test } from '@playwright/test';

// Auth e2e helpers: the mock gateway signs long-lived JWTs so the router guard's
// local `exp` check stays green for the whole deterministic flow.
function makeJwt(payload) {
    const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
    return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(payload)}.e2e-signature`;
}

const longLivedToken = makeJwt({ sub: 'demo-user', exp: 4102444800 });
const demoUser = { username: 'demo-user', displayName: '演示评审员', role: 'PRODUCT_MANAGER' };

// Captures the latest register request body for [AIREVIEW-PLAN-027] role assertions.
const authState = { lastRegisterBody: null };

function sessionBody(user = demoUser, token = longLivedToken) {
    return JSON.stringify({ token, user });
}

async function mockAuthRoutes(page, { loginStatus = 200, registerStatus = 200 } = {}) {
    await page.route('**/api/auth/login', (route) => route.fulfill(
        loginStatus === 200
            ? { status: 200, contentType: 'application/json', body: sessionBody() }
            : {
                status: loginStatus,
                contentType: 'application/json',
                body: JSON.stringify({ title: 'Unauthorized', detail: '用户名或密码错误', code: 'AUTH_BAD_CREDENTIALS' })
            }
    ));
    await page.route('**/api/auth/register', (route) => {
        authState.lastRegisterBody = route.request().postDataJSON();
        return route.fulfill(
            registerStatus === 200
                ? { status: 200, contentType: 'application/json', body: sessionBody() }
                : {
                    status: registerStatus,
                    contentType: 'application/json',
                    body: JSON.stringify({ title: 'Conflict', detail: '用户名已存在', code: 'AUTH_USERNAME_TAKEN' })
                }
        );
    });
    await page.route('**/api/auth/me', (route) => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify(demoUser)
    }));
}

async function mockDashboard(page) {
    await page.route('**/api/dashboard', (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            pendingRequirementCount: 0,
            activeReviewCount: 0,
            requirementStatusCounts: { DRAFT: 0, PENDING_REVIEW: 0, REVIEWING: 0, APPROVED: 0, REJECTED: 0, DEVELOPING: 0, DONE: 0 },
            activeReviews: [],
            recentActivities: []
        })
    }));
}

async function seedSession(page) {
    await page.addInitScript(({ token, user }) => {
        localStorage.setItem('chongming-auth', JSON.stringify({ token, user }));
    }, { token: longLivedToken, user: demoUser });
}

// 兜底拦截：具体路由未覆盖的 /api 请求不得经 vite 代理泄漏到本地后端（其 401 会清除会话并跳登录页）。
test.beforeEach(async ({ page }) => {
    await page.route((url) => url.pathname.startsWith('/api/'), (route) => route.fulfill({
        status: 404, contentType: 'application/json', body: JSON.stringify({ detail: 'not mocked' })
    }));
});

test('redirects anonymous visitors from protected pages to the login screen', async ({ page }) => {
    await mockAuthRoutes(page);
    await mockDashboard(page);

    await page.goto('/index.html#/dashboard');

    await expect(page).toHaveURL(/#\/login\?redirect=(%2F|\/)dashboard$/);
    await expect(page.locator('.auth-title')).toHaveText('登录');
    await expect(page.locator('input[name="username"]')).toBeVisible();
    await expect(page.locator('input[name="password"]')).toBeVisible();
    await expect(page.locator('.platform-sidebar')).toHaveCount(0);
});

test('keeps the login submit disabled until credentials are entered and links to registration', async ({ page }) => {
    await mockAuthRoutes(page);

    await page.goto('/index.html#/login');

    const submit = page.getByRole('button', { name: '登录' });
    await expect(submit).toBeDisabled();
    await page.locator('input[name="username"]').fill('demo-user');
    await expect(submit).toBeDisabled();
    await page.locator('input[name="password"]').fill('secret');
    await expect(submit).toBeEnabled();
    await expect(page.locator('.auth-switch')).toHaveText('还没有账号？去注册');
});

test('signs the user in and shows the display name in the workbench sidebar', async ({ page }) => {
    await mockAuthRoutes(page);
    await mockDashboard(page);

    await page.goto('/index.html#/login');
    await page.locator('input[name="username"]').fill('demo-user');
    await page.locator('input[name="password"]').fill('secret');
    await page.getByRole('button', { name: '登录' }).click();

    await expect(page).toHaveURL(/#\/dashboard$/);
    await expect(page.locator('.breadcrumb .cur')).toHaveText('工作台');
    await expect(page.locator('.user-area .user-name')).toHaveText('演示评审员');
    await expect(page.locator('.user-area .user-role')).toHaveText('demo-user');
    await expect(page.locator('.auth-logout')).toHaveText('退出登录');

    const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('chongming-auth')));
    expect(stored.token).toBe(longLivedToken);
    expect(stored.user).toEqual(demoUser);
});

test('honors the preserved redirect target after login', async ({ page }) => {
    await mockAuthRoutes(page);
    await mockDashboard(page);
    await page.route('**/api/requirements**', (route) => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ items: [], total: 0, page: 1, size: 20 })
    }));

    await page.goto('/index.html#/requirements');
    await expect(page).toHaveURL(/#\/login\?redirect=(%2F|\/)requirements$/);

    await page.locator('input[name="username"]').fill('demo-user');
    await page.locator('input[name="password"]').fill('secret');
    await page.getByRole('button', { name: '登录' }).click();

    await expect(page).toHaveURL(/#\/requirements$/);
    await expect(page.locator('.breadcrumb .cur')).toHaveText('需求库');
});

test('shows the ProblemDetail message when credentials are rejected', async ({ page }) => {
    await mockAuthRoutes(page, { loginStatus: 401 });
    await mockDashboard(page);

    await page.goto('/index.html#/login');
    await page.locator('input[name="username"]').fill('demo-user');
    await page.locator('input[name="password"]').fill('wrong');
    await page.getByRole('button', { name: '登录' }).click();

    await expect(page.locator('.error-banner')).toHaveText('用户名或密码错误');
    await expect(page).toHaveURL(/#\/login$/);
    const stored = await page.evaluate(() => localStorage.getItem('chongming-auth'));
    expect(stored).toBeNull();
});

test('logs the user out and returns to the login screen', async ({ page }) => {
    await seedSession(page);
    await mockAuthRoutes(page);
    await mockDashboard(page);

    await page.goto('/index.html#/dashboard');
    await expect(page.locator('.user-area .user-name')).toHaveText('演示评审员');

    await page.locator('.auth-logout').click();

    await expect(page).toHaveURL(/#\/login$/);
    await expect(page.locator('.auth-title')).toHaveText('登录');
    const stored = await page.evaluate(() => localStorage.getItem('chongming-auth'));
    expect(stored).toBeNull();
});

test('registers a new account, signs it in and enters the dashboard', async ({ page }) => {
    await mockAuthRoutes(page);
    await mockDashboard(page);

    await page.goto('/index.html#/register');

    await expect(page.locator('.auth-title')).toHaveText('注册');
    // [AIREVIEW-PLAN-027] 角色下拉默认为开发，可选产品经理/项目经理，不含 ADMIN。
    const roleSelect = page.locator('select[name="role"]');
    await expect(roleSelect).toHaveValue('DEVELOPER');
    await expect(roleSelect.locator('option')).toHaveText(['开发', '产品经理', '项目经理']);

    const submit = page.getByRole('button', { name: '注册' });
    await expect(submit).toBeDisabled();
    await page.locator('input[name="username"]').fill('demo-user');
    await page.locator('input[name="displayName"]').fill('演示评审员');
    // [AIREVIEW-PLAN-025] 公司 UID 可选，填写后随注册请求上送。
    await page.locator('input[name="companyUid"]').fill('corp-10086');
    await page.locator('input[name="password"]').fill('secret');
    await submit.click();

    await expect(page).toHaveURL(/#\/dashboard$/);
    await expect(page.locator('.user-area .user-name')).toHaveText('演示评审员');
    expect(authState.lastRegisterBody).toEqual({
        username: 'demo-user', password: 'secret', displayName: '演示评审员', role: 'DEVELOPER', uid: 'corp-10086'
    });
});

test('keeps the visitor on the register page when the username is taken', async ({ page }) => {
    await mockAuthRoutes(page, { registerStatus: 409 });

    await page.goto('/index.html#/register');
    await page.locator('input[name="username"]').fill('demo-user');
    await page.locator('input[name="displayName"]').fill('演示评审员');
    await page.locator('input[name="password"]').fill('secret');
    await page.getByRole('button', { name: '注册' }).click();

    await expect(page.locator('.error-banner')).toHaveText('用户名已存在');
    await expect(page).toHaveURL(/#\/register$/);
});

test('keeps signed-in users out of the auth pages', async ({ page }) => {
    await seedSession(page);
    await mockAuthRoutes(page);
    await mockDashboard(page);

    await page.goto('/index.html#/login');

    await expect(page).toHaveURL(/#\/dashboard$/);
    await expect(page.locator('.breadcrumb .cur')).toHaveText('工作台');
});
