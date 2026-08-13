/**
 * Thin REST client for the task-center endpoints (dispatch / acceptance workflow).
 * Reuses the shared `request()` pipeline from review-api so ProblemDetail parsing,
 * `ReviewApiError` semantics and Bearer-token injection stay identical.
 * Task status vocabulary: PENDING_ASSIGN / DEVELOPING / PENDING_ACCEPTANCE / DONE.
 */
import { request } from './review-api';

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

export const taskApi = {
    listTasks({ status, assignee, mine, requirementId, page = 1, size = 20 } = {}) {
        return request(withQuery('/api/tasks', { status, assignee, mine, requirementId, page, size }));
    },

    getTask(taskId) {
        return request(`/api/tasks/${taskId}`);
    },

    assign(taskId, { assigneeUsername, expectedVersion }) {
        return request(`/api/tasks/${taskId}/assign`, {
            method: 'POST', ...jsonBody({ assigneeUsername, expectedVersion })
        });
    },

    submitAcceptance(taskId, { expectedVersion }) {
        return request(`/api/tasks/${taskId}/submit-acceptance`, {
            method: 'POST', ...jsonBody({ expectedVersion })
        });
    },

    accept(taskId, { note, expectedVersion }) {
        return request(`/api/tasks/${taskId}/accept`, {
            method: 'POST', ...jsonBody({ note, expectedVersion })
        });
    },

    reject(taskId, { note, expectedVersion }) {
        return request(`/api/tasks/${taskId}/reject`, {
            method: 'POST', ...jsonBody({ note, expectedVersion })
        });
    }
};
