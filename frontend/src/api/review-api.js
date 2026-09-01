/**
 * [AIREVIEW-PLAN-012#1.3] Thin REST client for the public review API.
 * [AIREVIEW-PLAN-023#2] Loads safe repository identifiers from the active backend configuration.
 * [AIREVIEW-PLAN-023#3] Launches a draft requirement through one idempotent orchestration command.
 * [AIREVIEW-PLAN-024#方案5] Queries the five-status assessment projection and normalizes its fields.
 * Request and response values deliberately remain plain objects so API fixtures can exercise them directly.
 */

import { clearAuthSession, getAuthToken, redirectToLogin } from '../services/auth-token';

// [AIREVIEW-PLAN-024#方案5] Frozen five-status assessment vocabulary shared by Gate, report and workbench.
export const ASSESSMENT_STATUSES = ['CONFIRMED', 'PARTIAL', 'GAP', 'UNKNOWN', 'NOT_APPLICABLE'];

/**
 * [AIREVIEW-PLAN-024#方案5] Normalizes the AssessmentsView payload so views never branch on
 * missing fields. Counters keep the server values untouched; only shapes become safe.
 */
export function parseAssessmentsView(payload) {
    const source = payload && typeof payload === 'object' ? payload : {};
    const coverageSource = source.coverage && typeof source.coverage === 'object' ? source.coverage : {};
    const count = (value) => {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : 0;
    };
    const assessments = Array.isArray(source.assessments)
        ? source.assessments
            .filter((entry) => entry && typeof entry === 'object')
            .map((entry) => ({
                role: entry.role ?? null,
                checkpointKey: entry.checkpointKey ?? null,
                status: typeof entry.status === 'string' ? entry.status : 'UNKNOWN',
                summary: entry.summary ?? '',
                reasonSummary: entry.reasonSummary ?? '',
                evidenceIds: Array.isArray(entry.evidenceIds) ? entry.evidenceIds : [],
                createdAt: entry.createdAt ?? null
            }))
        : [];
    return {
        attempt: Number.isInteger(source.attempt) ? source.attempt : null,
        coverage: {
            required: count(coverageSource.required),
            covered: count(coverageSource.covered),
            confirmed: count(coverageSource.confirmed),
            partial: count(coverageSource.partial),
            gap: count(coverageSource.gap),
            unknown: count(coverageSource.unknown),
            notApplicable: count(coverageSource.notApplicable),
            uncoveredCheckpoints: Array.isArray(coverageSource.uncoveredCheckpoints)
                ? coverageSource.uncoveredCheckpoints.filter((value) => typeof value === 'string')
                : []
        },
        assessments
    };
}

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

export async function request(path, { method = 'GET', body, headers = {}, fetchImpl = fetch } = {}) {
    // Attach the Bearer token when a session exists; auth endpoints stay token-free.
    const token = getAuthToken();
    const mergedHeaders = token ? { Authorization: `Bearer ${token}`, ...headers } : headers;
    const response = await fetchImpl(apiPath(path), {
        method,
        headers: mergedHeaders,
        body
    });
    const parsed = await parseResponse(response);
    if (!response.ok) {
        if (response.status === 401 && !apiPath(path).startsWith('/api/auth/')) {
            clearAuthSession();
            redirectToLogin();
        }
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

/**
 * [AIREVIEW-PLAN-031#2] Binary download companion of {@link request}: returns the raw blob plus
 * the RFC 5987 file name from Content-Disposition instead of parsing a JSON/text body.
 */
export async function requestBlob(path, { fetchImpl = fetch } = {}) {
    const token = getAuthToken();
    const response = await fetchImpl(apiPath(path), {
        method: 'GET',
        headers: token ? { Authorization: `Bearer ${token}` } : {}
    });
    if (!response.ok) {
        if (response.status === 401 && !apiPath(path).startsWith('/api/auth/')) {
            clearAuthSession();
            redirectToLogin();
        }
        throw new ReviewApiError(`下载失败（HTTP ${response.status}）`, { status: response.status });
    }
    const disposition = response.headers.get('content-disposition') ?? '';
    const match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
    return {
        blob: await response.blob(),
        fileName: match ? decodeURIComponent(match[1]) : null
    };
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
    listRepositories() {
        return request('/api/repositories');
    },

    getDashboard() {
        return request('/api/dashboard');
    },

    listRequirements({ status, assignee, keyword, page = 1, size = 20 } = {}) {
        return request(withQuery('/api/requirements', { status, assignee, keyword, page, size }));
    },

    createRequirement(draft) {
        return request('/api/requirements', { method: 'POST', ...jsonBody(draft) });
    },

    getRequirement(requirementId) {
        return request(`/api/requirements/${requirementId}`);
    },

    // [AIREVIEW-PLAN-111] Uploaded Markdown snapshot for the requirement's active review attempt.
    getRequirementDocument(requirementId) {
        return request(`/api/requirements/${requirementId}/document`);
    },

    reviseRequirement(requirementId, draft) {
        return request(`/api/requirements/${requirementId}`, { method: 'PUT', ...jsonBody(draft) });
    },

    deleteRequirement(requirementId, expectedVersion) {
        return request(withQuery(`/api/requirements/${requirementId}`, { expectedVersion }), { method: 'DELETE' });
    },

    submitRequirement(requirementId, { reviewId, expectedVersion }) {
        return request(`/api/requirements/${requirementId}/submit`, {
            method: 'POST', ...jsonBody({ reviewId, expectedVersion })
        });
    },

    launchRequirementReview(requirementId, {
        requirementFile,
        requirementText,
        repositoryPath,
        branch,
        commit,
        submitter,
        publicTasks,
        changeReason,
        initialMessage,
        expectedVersion,
        idempotencyKey,
        traceId
    }) {
        const form = new FormData();
        // [AIREVIEW-PLAN-025] The Markdown travels either as an uploaded .md part or typed text.
        if (requirementFile) form.append('requirementFile', requirementFile);
        if (requirementText) form.append('requirementText', requirementText);
        // [AIREVIEW-PLAN-029] Remote-bound requirements launch without a configured repository id.
        if (repositoryPath) form.append('repositoryPath', repositoryPath);
        if (branch) form.append('branch', branch);
        if (commit) form.append('commit', commit);
        form.append('submitter', submitter);
        form.append('publicTasks', JSON.stringify(publicTasks));
        form.append('changeReason', changeReason);
        form.append('initialMessage', initialMessage);
        form.append('expectedVersion', String(expectedVersion));
        return request(`/api/requirements/${requirementId}/reviews`, {
            method: 'POST',
            body: form,
            headers: {
                'Idempotency-Key': idempotencyKey,
                ...(traceId ? { 'X-Trace-Id': traceId } : {})
            }
        });
    },

    startRequirementDevelopment(requirementId, expectedVersion) {
        return request(`/api/requirements/${requirementId}/start-development`, {
            method: 'POST', ...jsonBody({ expectedVersion })
        });
    },

    completeRequirement(requirementId, expectedVersion) {
        return request(`/api/requirements/${requirementId}/complete`, {
            method: 'POST', ...jsonBody({ expectedVersion })
        });
    },

    cancelRequirement(requirementId, expectedVersion) {
        return request(`/api/requirements/${requirementId}/cancel`, {
            method: 'POST', ...jsonBody({ expectedVersion })
        });
    },

    listReviews({ stage, hasReport, page = 1, size = 20 } = {}) {
        return request(withQuery('/api/reviews', { stage, hasReport, page, size }));
    },

    listReports({ page = 1, size = 20 } = {}) {
        return request(withQuery('/api/reports', { page, size }));
    },

    createReview({
        requirementFile,
        requirementText,
        repositoryPath,
        branch,
        commit,
        submitter,
        forceNewAttempt = false,
        remoteUrl,
        remoteRef,
        remoteToken
    }) {
        const form = new FormData();
        // [AIREVIEW-PLAN-025] The Markdown travels either as an uploaded .md part or typed text.
        if (requirementFile) form.append('requirementFile', requirementFile);
        if (requirementText) form.append('requirementText', requirementText);
        // [AIREVIEW-PLAN-029] Configured repositories and online sources are mutually exclusive.
        if (repositoryPath) form.append('repositoryPath', repositoryPath);
        form.append('submitter', submitter);
        if (branch) form.append('branch', branch);
        if (commit) form.append('commit', commit);
        if (remoteUrl) form.append('remoteUrl', remoteUrl);
        if (remoteRef) form.append('remoteRef', remoteRef);
        if (remoteToken) form.append('remoteToken', remoteToken);
        form.append('forceNewAttempt', String(forceNewAttempt));
        return request('/api/reviews', { method: 'POST', body: form });
    },

    startReview(reviewId, {
        expectedVersion,
        idempotencyKey,
        userId,
        publicTasks,
        changeReason,
        initialMessage,
        traceId
    }) {
        const payload = jsonBody({ expectedVersion, userId, publicTasks, changeReason, initialMessage });
        return request(`/api/reviews/${reviewId}/start`, {
            method: 'POST',
            body: payload.body,
            headers: {
                ...payload.headers,
                'Idempotency-Key': idempotencyKey,
                ...(traceId ? { 'X-Trace-Id': traceId } : {})
            }
        });
    },

    cancelReview(reviewId, expectedVersion) {
        return request(withQuery(`/api/reviews/${reviewId}/cancel`, { expectedVersion }), { method: 'POST' });
    },

    retryReview(reviewId, expectedVersion) {
        return request(withQuery(`/api/reviews/${reviewId}/retry`, { expectedVersion }), { method: 'POST' });
    },

    startScoutPreview(reviewId, attemptNo, { userId, traceId } = {}) {
        const payload = jsonBody({ userId });
        return request(`/api/reviews/${reviewId}/attempts/${attemptNo}/scout-previews`, {
            method: 'POST',
            body: payload.body,
            headers: { ...payload.headers, ...(traceId ? { 'X-Trace-Id': traceId } : {}) }
        });
    },

    getScoutPreview(reviewId, attemptNo, previewId) {
        return request(`/api/reviews/${reviewId}/attempts/${attemptNo}/scout-previews/${previewId}`);
    },

    getSummary(reviewId) {
        return request(`/api/reviews/${reviewId}`);
    },

    getPlans: fetchAllPlans,

    getDebates(reviewId) {
        return request(`/api/reviews/${reviewId}/debates`);
    },

    getClaims(reviewId) {
        return request(`/api/reviews/${reviewId}/claims`);
    },

    // [AIREVIEW-PLAN-024#方案5] Five-status checkpoint projection for the live workbench.
    getAssessments(reviewId) {
        return request(`/api/reviews/${reviewId}/assessments`);
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
