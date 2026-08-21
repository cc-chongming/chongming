/**
 * Thin REST client for the task-center endpoints (dispatch / acceptance workflow).
 * Reuses the shared `request()` pipeline from review-api so ProblemDetail parsing,
 * `ReviewApiError` semantics and Bearer-token injection stay identical.
 * Task status vocabulary: PENDING_ASSIGN / DEVELOPING / PAUSED / PENDING_ACCEPTANCE / DONE / CANCELLED.
 */
import { request, requestBlob } from './review-api';

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
    },

    // [AIREVIEW-PLAN-030] Multi-hop flow commands.
    handoff(taskId, { toUsername, note, expectedVersion }) {
        return request(`/api/tasks/${taskId}/handoff`, {
            method: 'POST', ...jsonBody({ toUsername, note, expectedVersion })
        });
    },

    pause(taskId, { note, expectedVersion }) {
        return request(`/api/tasks/${taskId}/pause`, {
            method: 'POST', ...jsonBody({ note, expectedVersion })
        });
    },

    resume(taskId, { note, expectedVersion }) {
        return request(`/api/tasks/${taskId}/resume`, {
            method: 'POST', ...jsonBody({ note, expectedVersion })
        });
    },

    cancel(taskId, { note, expectedVersion }) {
        return request(`/api/tasks/${taskId}/cancel`, {
            method: 'POST', ...jsonBody({ note, expectedVersion })
        });
    },

    // [AIREVIEW-PLAN-031#2] Handoff directory plus delivery attachments.
    listAssignableUsers() {
        return request('/api/tasks/assignable-users');
    },

    listAttachments(taskId) {
        return request(`/api/tasks/${taskId}/attachments`);
    },

    uploadAttachment(taskId, file) {
        const form = new FormData();
        form.append('file', file);
        return request(`/api/tasks/${taskId}/attachments`, { method: 'POST', body: form });
    },

    downloadAttachment(taskId, attachmentId) {
        return requestBlob(`/api/tasks/${taskId}/attachments/${attachmentId}`);
    },

    deleteAttachment(taskId, attachmentId) {
        return request(`/api/tasks/${taskId}/attachments/${attachmentId}`, { method: 'DELETE' });
    }
};
