/**
 * [AIREVIEW-PLAN-012#1.3] Thin REST client for the public review API.
 * Request and response values deliberately remain plain objects so API fixtures can exercise them directly.
 */

export class ReviewApiError extends Error {
    constructor(message, { status, errorCode, traceId, body } = {}) {
        super(message);
        this.name = 'ReviewApiError';
        this.status = status;
        this.errorCode = errorCode ?? 'REQUEST_FAILED';
        this.traceId = traceId ?? null;
        this.body = body ?? null;
    }
}

function apiPath(path) {
    return path.startsWith('/') ? path : `/${path}`;
}

async function parseResponse(response) {
    const contentType = response.headers.get('content-type') ?? '';
    if (contentType.includes('json')) {
        return response.json();
    }
    const text = await response.text();
    return text || null;
}

async function request(path, { method = 'GET', body, headers = {}, fetchImpl = fetch } = {}) {
    const response = await fetchImpl(apiPath(path), {
        method,
        headers,
        body
    });
    const parsed = await parseResponse(response);
    if (!response.ok) {
        const detail = typeof parsed === 'object' && parsed !== null ? parsed.detail : null;
        throw new ReviewApiError(detail || `请求失败（HTTP ${response.status}）`, {
            status: response.status,
            errorCode: typeof parsed === 'object' && parsed !== null ? (parsed.code ?? parsed.errorCode) : null,
            traceId: response.headers.get('x-trace-id') ?? response.headers.get('trace-id'),
            body: parsed
        });
    }
    return parsed;
}

function jsonBody(value) {
    return {
        body: JSON.stringify(value),
        headers: { 'Content-Type': 'application/json' }
    };
}

function withQuery(path, parameters) {
    const query = new URLSearchParams(
        Object.entries(parameters).filter(([, value]) => value !== undefined && value !== null && value !== '')
    );
    return query.size === 0 ? path : `${path}?${query.toString()}`;
}

async function fetchAllPlans(reviewId) {
    const items = [];
    let afterSequence = 0;
    do {
        const page = await request(withQuery(`/api/reviews/${reviewId}/plans`, { afterSequence, limit: 100 }));
        items.push(...(page.items ?? []));
        afterSequence = page.nextAfterSequence;
    } while (afterSequence !== null && afterSequence !== undefined);
    return items.sort((left, right) => left.sequence - right.sequence);
}

export const reviewApi = {
    createReview({ requirementFile, repositoryPath, branch, commit, submitter, forceNewAttempt = false }) {
        const form = new FormData();
        form.append('requirementFile', requirementFile);
        form.append('repositoryPath', repositoryPath);
        form.append('submitter', submitter);
        if (branch) form.append('branch', branch);
        if (commit) form.append('commit', commit);
        form.append('forceNewAttempt', String(forceNewAttempt));
        return request('/api/reviews', { method: 'POST', body: form });
    },

    getSummary(reviewId) {
        return request(`/api/reviews/${reviewId}`);
    },

    getPlans: fetchAllPlans,

    getDebates(reviewId) {
        return request(`/api/reviews/${reviewId}/debates`);
    },

    getEvidence(reviewId, evidenceId) {
        return request(`/api/reviews/${reviewId}/evidence/${evidenceId}`);
    },

    getHumanItems(reviewId, severity) {
        return request(withQuery(`/api/reviews/${reviewId}/human-review-items`, { severity }));
    },

    createHumanItem(reviewId, draft) {
        return request(`/api/reviews/${reviewId}/human-review-items`, {
            method: 'POST',
            ...jsonBody(draft)
        });
    },

    updateHumanItem(reviewId, itemId, expectedVersion, draft) {
        return request(withQuery(`/api/reviews/${reviewId}/human-review-items/${itemId}`, { expectedVersion }), {
            method: 'PATCH',
            ...jsonBody(draft)
        });
    },

    deleteHumanItem(reviewId, itemId, expectedVersion) {
        return request(withQuery(`/api/reviews/${reviewId}/human-review-items/${itemId}`, { expectedVersion }), {
            method: 'DELETE'
        });
    },

    getHumanGateVersions(reviewId) {
        return request(`/api/reviews/${reviewId}/human-gate-decisions`);
    },

    createHumanGateDecision(reviewId, decision) {
        return request(`/api/reviews/${reviewId}/human-gate-decisions`, {
            method: 'POST',
            ...jsonBody(decision)
        });
    },

    getReport(reviewId, { version, format = 'json' } = {}) {
        return request(withQuery(`/api/reviews/${reviewId}/report`, { version, format }));
    },

    getReportVersions(reviewId) {
        return request(`/api/reviews/${reviewId}/report/versions`);
    },

    generateReport(reviewId) {
        return request(`/api/reviews/${reviewId}/report`, { method: 'POST' });
    },

    getNotifications(reviewId) {
        return request(`/api/reviews/${reviewId}/notifications`);
    },

    retryNotification(reviewId, notificationId, expectedVersion) {
        return request(withQuery(`/api/reviews/${reviewId}/notifications/${notificationId}/retry`, { expectedVersion }), {
            method: 'POST'
        });
    }
};

export function formatApiError(error) {
    if (!(error instanceof ReviewApiError)) {
        return '网络或客户端错误，请稍后重试。';
    }
    const parts = [error.message, `错误码：${error.errorCode}`];
    if (error.traceId) parts.push(`追踪号：${error.traceId}`);
    return parts.join('；');
}
