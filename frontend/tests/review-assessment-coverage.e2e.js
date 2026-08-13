import { expect, test } from '@playwright/test';

// [AIREVIEW-PLAN-024#方案7] Deterministic browser fixture for five-status coverage projection.

const reviewId = 'b0000000-0000-0000-0000-000000000024';

async function mockPlatformDashboard(page) {
    await page.route('**/api/dashboard', (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
            pendingRequirementCount: 0,
            activeReviewCount: 1,
            requirementStatusCounts: {},
            activeReviews: [],
            recentActivities: []
        })
    }));
}

test('shows positive findings, unknown evidence and required-checkpoint coverage together', async ({ page }) => {
    await mockPlatformDashboard(page);
    await page.route('**/api/reviews/**', async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const json = (body, status = 200) => route.fulfill({
            status,
            contentType: 'application/json',
            body: JSON.stringify(body)
        });

        if (path.endsWith('/events') || path.endsWith('/runtime/ag-ui')) {
            return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
        }
        if (path === `/api/reviews/${reviewId}`) {
            return json({
                reviewId,
                attempt: 1,
                stage: 'INITIAL_REVIEW',
                progress: 40,
                lastSequence: 4,
                reviewVersion: 8,
                occurredAt: '2026-08-11 11:30:00',
                gate: null,
                activatedRoles: [
                    { role: 'PRODUCT', agentLabel: 'product-reviewer', initialReviewCompleted: true },
                    { role: 'FRONTEND', agentLabel: 'frontend-reviewer', initialReviewCompleted: false }
                ]
            });
        }
        if (path.endsWith('/assessments')) {
            return json({
                attempt: 1,
                coverage: {
                    required: 5,
                    covered: 4,
                    confirmed: 2,
                    partial: 1,
                    gap: 0,
                    unknown: 1,
                    notApplicable: 0,
                    uncoveredCheckpoints: ['FRONTEND:accessibility']
                },
                assessments: [
                    {
                        role: 'PRODUCT',
                        checkpointKey: 'requirement_value',
                        status: 'CONFIRMED',
                        summary: '需求价值与验收目标合理。',
                        reasonSummary: '验收标准可测量。',
                        evidenceIds: [],
                        createdAt: '2026-08-11T03:30:00Z'
                    },
                    {
                        role: 'PRODUCT',
                        checkpointKey: 'requirement_scope',
                        status: 'CONFIRMED',
                        summary: '需求范围已确认无问题。',
                        reasonSummary: '边界与非目标已明确。',
                        evidenceIds: [],
                        createdAt: '2026-08-11T03:30:01Z'
                    },
                    {
                        role: 'FRONTEND',
                        checkpointKey: 'interaction_contract',
                        status: 'PARTIAL',
                        summary: '交互契约部分满足。',
                        reasonSummary: '缺少空状态说明。',
                        evidenceIds: [],
                        createdAt: '2026-08-11T03:30:02Z'
                    },
                    {
                        role: 'FRONTEND',
                        checkpointKey: 'snapshot_grant_scope',
                        status: 'UNKNOWN',
                        summary: '当前授权证据不足。',
                        reasonSummary: '未授予可读前端 fileRef。',
                        evidenceIds: [],
                        createdAt: '2026-08-11T03:30:03Z'
                    }
                ]
            });
        }
        if (path.endsWith('/plans')) return json({ items: [], nextAfterSequence: null });
        if (path.endsWith('/debates') || path.endsWith('/claims')
            || path.endsWith('/human-review-items') || path.endsWith('/human-gate-decisions')
            || path.endsWith('/notifications') || path.endsWith('/report/versions')) return json([]);
        if (path.endsWith('/report')) return json({ detail: 'Not found' }, 404);
        return json({});
    });

    await page.goto(`/index.html#/reviews/${reviewId}`);

    await expect(page.getByRole('heading', { name: '检查点覆盖' })).toBeVisible();
    await expect(page.getByRole('progressbar', { name: '必检检查点覆盖 80%' })).toHaveAttribute('aria-valuenow', '80');
    await expect(page.getByText('确认无问题', { exact: true })).toBeVisible();
    await expect(page.getByText('执行但未知')).toBeVisible();
    await expect(page.getByText('确认 2', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('部分满足 1', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('证据不足 1', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('需求价值与验收目标合理。')).toBeVisible();
    await expect(page.getByText('当前授权证据不足。')).toBeVisible();
});

test('completes waiting human review through notifying with report version and sent notification', async ({ page }) => {
    await mockPlatformDashboard(page);
    let gateSubmitted = false;
    let summaryReadsAfterGate = 0;
    const gateRequests = [];
    const gateDecision = {
        gateVersion: 1,
        result: 'PASS',
        reason: '人工确认高风险未知项已有线下处置方案。',
        conditions: [],
        decidedAt: '2026-08-11 11:55:00'
    };
    const report = {
        summary: { stage: 'COMPLETED', occurredAt: '2026-08-11 11:56:00' },
        claims: [],
        debates: [],
        gateDecisions: [gateDecision],
        assessments: {
            required: 1,
            covered: 1,
            confirmed: 0,
            partial: 0,
            gap: 0,
            unknown: 1,
            notApplicable: 0,
            confirmedEntries: [],
            partialEntries: [],
            gapEntries: [],
            unknownEntries: [{ role: 'BACKEND', checkpointKey: 'runtime_dependency', status: 'UNKNOWN', summary: '运行依赖需人工确认。' }],
            notApplicableEntries: []
        }
    };

    await page.route('**/api/reviews/**', async (route) => {
        const requestUrl = new URL(route.request().url());
        const path = requestUrl.pathname;
        const method = route.request().method();
        const json = (body, status = 200) => route.fulfill({
            status,
            contentType: 'application/json',
            body: JSON.stringify(body)
        });
        if (path.endsWith('/events') || path.endsWith('/runtime/ag-ui')) {
            return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
        }
        if (path === `/api/reviews/${reviewId}`) {
            let stage = 'WAITING_HUMAN';
            if (gateSubmitted) {
                stage = summaryReadsAfterGate++ === 0 ? 'NOTIFYING' : 'COMPLETED';
            }
            return json({
                reviewId,
                attempt: 1,
                stage,
                progress: stage === 'COMPLETED' ? 100 : 90,
                lastSequence: stage === 'COMPLETED' ? 18 : 16,
                reviewVersion: gateSubmitted ? 13 : 12,
                occurredAt: '2026-08-11 11:54:00',
                gate: gateSubmitted
                    ? { result: 'PASS', status: 'FINAL', reasonSummary: gateDecision.reason }
                    : { result: 'HUMAN_REQUIRED', status: 'DRAFT', reasonSummary: '存在高风险 UNKNOWN。' }
            });
        }
        if (path.endsWith('/human-gate-decisions') && method === 'POST') {
            gateRequests.push(route.request().postDataJSON());
            gateSubmitted = true;
            return json(gateDecision, 201);
        }
        if (path.endsWith('/human-gate-decisions')) return json(gateSubmitted ? [gateDecision] : []);
        if (path.endsWith('/notifications')) {
            return json(gateSubmitted ? [{
                notificationId: 'n-024',
                deliveryStatus: 'SENT',
                command: { channel: 'LOCAL', gateVersion: 1 },
                responseCode: '200',
                version: 1
            }] : []);
        }
        if (path.endsWith('/report/versions')) {
            return json(gateSubmitted ? [{ reportVersion: 1, createdAt: '2026-08-11 11:56:00' }] : []);
        }
        if (path.endsWith('/report') && requestUrl.searchParams.get('format') === 'markdown') {
            return route.fulfill({ status: 200, contentType: 'text/markdown', body: '# 评审报告 v1' });
        }
        if (path.endsWith('/report')) return gateSubmitted ? json(report) : json({ detail: 'Not found' }, 404);
        if (path.endsWith('/assessments')) {
            return json({
                attempt: 1,
                coverage: { required: 1, covered: 1, confirmed: 0, partial: 0, gap: 0, unknown: 1, notApplicable: 0, uncoveredCheckpoints: [] },
                assessments: [{ role: 'BACKEND', checkpointKey: 'runtime_dependency', status: 'UNKNOWN', summary: '运行依赖需人工确认。', reasonSummary: '缺少运行环境证据。', evidenceIds: [] }]
            });
        }
        if (path.endsWith('/plans')) return json({ items: [], nextAfterSequence: null });
        if (path.endsWith('/debates') || path.endsWith('/claims') || path.endsWith('/human-review-items')) return json([]);
        return json({});
    });

    await page.goto(`/index.html#/reviews/${reviewId}`);
    await expect(page.getByText('WAITING_HUMAN', { exact: true }).first()).toBeVisible();
    await page.getByRole('combobox', { name: '结论', exact: true }).selectOption('PASS');
    await page.getByRole('textbox', { name: '理由', exact: true }).fill(gateDecision.reason);
    await page.getByRole('button', { name: '提交最终 Gate' }).click();

    await expect.poll(() => gateRequests.length).toBe(1);
    expect(gateRequests[0]).toMatchObject({ expectedVersion: 12, result: 'PASS', reason: gateDecision.reason });
    await expect(page.getByText('SENT', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '刷新状态' }).click();
    await expect(page.getByText('NOTIFYING', { exact: true }).first()).toBeVisible();
    await page.getByRole('button', { name: '刷新状态' }).click();
    await expect(page.getByText('COMPLETED', { exact: true }).first()).toBeVisible();

    await page.getByRole('link', { name: '查看最终报告' }).click();
    await expect(page.getByText('· 报告 v1', { exact: true })).toBeVisible();
    await expect(page.getByText('运行依赖需人工确认。')).toBeVisible();
    await expect(page.getByText('共 1 个版本')).toBeVisible();
});
