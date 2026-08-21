<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { formatApiError, ReviewApiError } from '../api/review-api';
import { taskApi } from '../api/task-api';
import { authStore } from '../stores/auth-store';
import { formatChinaTime } from '../services/china-time';

/**
 * Task-center detail page: info panel + role/status-driven actions.
 * Every command carries the current `task.version` as `expectedVersion`;
 * VERSION_CONFLICT (HTTP 409) refreshes the view and asks the user to retry.
 */
const props = defineProps({ taskId: { type: String, required: true } });

const task = ref(null);
const loading = ref(true);
const changing = ref(false);
const error = ref('');
const note = ref('');
// [AIREVIEW-PLAN-030] Multi-hop flow inputs.
const handoffTo = ref('');
const flowNote = ref('');
// [AIREVIEW-PLAN-031#2] Handoff directory (non-admin accounts) and delivery attachments.
const assignableUsers = ref([]);
const attachments = ref([]);
const uploading = ref(false);
const fileInput = ref(null);

const statusMeta = {
    PENDING_ASSIGN: { tag: 'tag-pending', label: '待指派' },
    DEVELOPING: { tag: 'tag-dev', label: '开发中' },
    PAUSED: { tag: 'tag-pending', label: '暂停中' },
    PENDING_ACCEPTANCE: { tag: 'tag-review', label: '待验收' },
    DONE: { tag: 'tag-done', label: '已完成' },
    CANCELLED: { tag: 'tag-draft', label: '已关闭' }
};
const currentStatus = computed(() => statusMeta[task.value?.status]
    ?? { tag: 'tag-draft', label: task.value?.status ?? '—' });

const currentUser = computed(() => authStore.currentUser.value);
const isAdmin = computed(() => currentUser.value?.role === 'ADMIN');
// [AIREVIEW-PLAN-030] The current holder (or ADMIN) drives handoff / pause / resume.
const isHolder = computed(() => !!currentUser.value?.username
    && (task.value?.currentHolderUsername || task.value?.assigneeUsername) === currentUser.value.username);
const canFlow = computed(() => ['DEVELOPING', 'PAUSED'].includes(task.value?.status) && (isHolder.value || isAdmin.value));
// Assignee submits acceptance while developing; the requirement creator or ADMIN decides during pending acceptance.
const canSubmitAcceptance = computed(() => task.value?.status === 'DEVELOPING' && isHolder.value);
const canAcceptance = computed(() => task.value?.status === 'PENDING_ACCEPTANCE'
    && (isAdmin.value || (!!currentUser.value?.username && currentUser.value.username !== task.value?.assigneeUsername)));
// [AIREVIEW-PLAN-031#2] The holder (or ADMIN) may attach delivery files until the task closes.
const canUploadAttachments = computed(() => (isHolder.value || isAdmin.value)
    && ['DEVELOPING', 'PAUSED', 'PENDING_ACCEPTANCE'].includes(task.value?.status));
const handoffOptions = computed(() => {
    const holder = task.value?.currentHolderUsername || task.value?.assigneeUsername;
    return assignableUsers.value.filter((user) => user.username !== holder);
});

function shortId(id) {
    return id ? `#${String(id).slice(0, 8)}` : '—';
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        task.value = await taskApi.getTask(props.taskId);
        await Promise.all([loadAttachments(), loadAssignableUsers()]);
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        loading.value = false;
    }
}

// [AIREVIEW-PLAN-031#2] Attachment list refreshes with the task; failures stay non-blocking.
async function loadAttachments() {
    try {
        attachments.value = await taskApi.listAttachments(props.taskId);
    } catch {
        attachments.value = [];
    }
}

async function loadAssignableUsers() {
    if (assignableUsers.value.length) return;
    try {
        assignableUsers.value = await taskApi.listAssignableUsers();
    } catch {
        assignableUsers.value = [];
    }
}

async function uploadAttachment() {
    const file = fileInput.value?.files?.[0];
    if (!file) return;
    uploading.value = true;
    error.value = '';
    try {
        await taskApi.uploadAttachment(props.taskId, file);
        await loadAttachments();
        if (fileInput.value) fileInput.value.value = '';
    } catch (requestError) {
        error.value = requestError?.status === 413
            ? '文件超过上传限制（单个 ≤20MB），请压缩或拆分后重试。'
            : formatApiError(requestError);
    } finally {
        uploading.value = false;
    }
}

async function downloadAttachment(entry) {
    try {
        const { blob, fileName } = await taskApi.downloadAttachment(props.taskId, entry.attachmentId);
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = fileName || entry.fileName;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (requestError) {
        error.value = formatApiError(requestError);
    }
}

async function removeAttachment(entry) {
    changing.value = true;
    error.value = '';
    try {
        await taskApi.deleteAttachment(props.taskId, entry.attachmentId);
        await loadAttachments();
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        changing.value = false;
    }
}

function canManageAttachment(entry) {
    return isAdmin.value || entry.uploadedBy === currentUser.value?.username;
}

function formatBytes(size) {
    if (!Number.isFinite(size) || size < 0) return '—';
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

async function runCommand(executor) {
    if (!task.value) return;
    changing.value = true;
    error.value = '';
    try {
        task.value = await executor();
        note.value = '';
    } catch (requestError) {
        if (requestError instanceof ReviewApiError && requestError.errorCode === 'VERSION_CONFLICT') {
            await load();
            error.value = '任务版本已刷新，请核对最新状态后重试。';
        } else {
            error.value = formatApiError(requestError);
        }
    } finally {
        changing.value = false;
    }
}

function submitAcceptance() {
    runCommand(() => taskApi.submitAcceptance(props.taskId, { expectedVersion: task.value.version }));
}

function accept() {
    runCommand(() => taskApi.accept(props.taskId, { note: note.value.trim(), expectedVersion: task.value.version }));
}

function reject() {
    runCommand(() => taskApi.reject(props.taskId, { note: note.value.trim(), expectedVersion: task.value.version }));
}

// [AIREVIEW-PLAN-030] Multi-hop flow commands.
function handoff() {
    runCommand(() => taskApi.handoff(props.taskId, {
        toUsername: handoffTo.value.trim(), note: flowNote.value.trim(), expectedVersion: task.value.version
    }));
}

function pause() {
    runCommand(() => taskApi.pause(props.taskId, { note: flowNote.value.trim(), expectedVersion: task.value.version }));
}

function resume() {
    runCommand(() => taskApi.resume(props.taskId, { note: flowNote.value.trim(), expectedVersion: task.value.version }));
}

function cancelTask() {
    runCommand(() => taskApi.cancel(props.taskId, { note: flowNote.value.trim(), expectedVersion: task.value.version }));
}

watch(() => props.taskId, () => {
    note.value = '';
    load();
});
onMounted(load);
</script>

<template>
    <section class="platform-page">
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <p v-if="loading" class="empty-note">正在读取任务详情…</p>
        <template v-else-if="task">
            <div class="rd-top">
                <div class="rd-info">
                    <h1>{{ task.title }}</h1>
                    <div class="rd-meta">
                        <span class="rd-meta-item"><span class="rd-mono">{{ shortId(task.taskId) }}</span></span>
                        <span class="rd-meta-item"><span class="tag" :class="currentStatus.tag">{{ currentStatus.label }}</span></span>
                        <span class="rd-meta-item">👤 负责人：{{ task.assigneeDisplayName || task.assigneeUsername || '未指派' }}</span>
                        <span class="rd-meta-item">🕐 {{ formatChinaTime(task.updatedAt) }}</span>
                    </div>
                </div>
                <div class="rd-actions">
                    <RouterLink class="button secondary" to="/tasks">返回任务列表</RouterLink>
                    <RouterLink v-if="task.requirementId" class="button secondary" :to="`/requirements/${task.requirementId}`">查看关联需求</RouterLink>
                </div>
            </div>

            <div class="rd-grid">
                <div>
                    <div class="card" style="margin-bottom:16px">
                        <div class="card-hd"><span class="card-ico">🗂</span><div class="card-nm">任务信息</div></div>
                        <div class="card-bd" style="padding:8px 16px">
                            <div class="sb-section">
                                <div class="sb-row"><span class="role-name">关联需求</span>
                                    <span class="role-count"><RouterLink v-if="task.requirementId" :to="`/requirements/${task.requirementId}`">{{ task.requirementTitle || shortId(task.requirementId) }}</RouterLink><template v-else>—</template></span></div>
                                <div class="sb-row"><span class="role-name">关联评审</span>
                                    <span class="role-count"><RouterLink v-if="task.reviewId" :to="`/reviews/${task.reviewId}/live`">{{ shortId(task.reviewId) }}</RouterLink><template v-else>—</template></span></div>
                                <div class="sb-row"><span class="role-name">指派人</span><span class="role-count">{{ task.dispatcherUsername || '—' }}</span></div>
                                <div class="sb-row"><span class="role-name">创建时间</span><span class="role-count">{{ task.createdAt ? formatChinaTime(task.createdAt) : '—' }}</span></div>
                                <div class="sb-row"><span class="role-name">更新时间</span><span class="role-count">{{ task.updatedAt ? formatChinaTime(task.updatedAt) : '—' }}</span></div>
                            </div>
                        </div>
                    </div>

                    <div class="card">
                        <div class="card-hd"><span class="card-ico">✅</span><div class="card-nm">验收备注</div></div>
                        <div class="card-bd">
                            <p v-if="task.acceptanceNote" class="rd-doc" style="white-space:pre-wrap">{{ task.acceptanceNote }}</p>
                            <p v-else class="dash-empty">暂无验收备注。</p>
                        </div>
                    </div>

                    <!-- [AIREVIEW-PLAN-031#2] Delivery attachments shared by handoff and acceptance. -->
                    <div class="card" style="margin-top:16px">
                        <div class="card-hd"><span class="card-ico">📎</span><div class="card-nm">任务附件</div></div>
                        <div class="card-bd">
                            <ul v-if="attachments.length" class="plan-history">
                                <li v-for="entry in attachments" :key="entry.attachmentId">
                                    <strong><a href="#" @click.prevent="downloadAttachment(entry)">{{ entry.fileName }}</a></strong>
                                    <span>{{ formatBytes(entry.fileSize) }} · {{ entry.uploadedBy }} · {{ formatChinaTime(entry.createdAt) }}</span>
                                    <p v-if="canManageAttachment(entry)"><button class="text-button" type="button" :disabled="changing" @click="removeAttachment(entry)">删除</button></p>
                                </li>
                            </ul>
                            <p v-else class="dash-empty">暂无附件。开发完成后可在此上传交付文件，供下一负责人与验收人查看。</p>
                            <form v-if="canUploadAttachments" class="review-form compact" style="margin-top:8px" @submit.prevent="uploadAttachment">
                                <label class="full">上传附件（单个 ≤20MB）<input ref="fileInput" type="file" required /></label>
                                <div class="form-actions full"><button class="button secondary" type="submit" :disabled="uploading">上传</button></div>
                            </form>
                        </div>
                    </div>

                    <!-- [AIREVIEW-PLAN-030] Multi-hop flow: handoff / pause / resume / cancel. -->
                    <div v-if="canFlow || isAdmin" class="card" style="margin-top:16px">
                        <div class="card-hd"><span class="card-ico">🔁</span><div class="card-nm">流转操作</div></div>
                        <div class="card-bd">
                            <form v-if="canFlow && task.status === 'DEVELOPING'" class="review-form compact" @submit.prevent="handoff">
                                <label>流转给
                                    <select v-model="handoffTo" required>
                                        <option value="" disabled>选择下一负责人</option>
                                        <option v-for="user in handoffOptions" :key="user.username" :value="user.username">{{ user.displayName || user.username }}（{{ user.username }}）</option>
                                    </select>
                                </label>
                                <label class="full">流转说明（可选）<input v-model.trim="flowNote" maxlength="512" /></label>
                                <div class="form-actions full"><button class="button" type="submit" :disabled="changing || !handoffTo">流转给下一负责人</button></div>
                            </form>
                            <form v-if="canFlow" class="review-form compact" style="margin-top:8px" @submit.prevent="task.status === 'PAUSED' ? resume() : pause()">
                                <label class="full">{{ task.status === 'PAUSED' ? '恢复说明（可选）' : '暂停原因（必填）' }}<input v-model.trim="flowNote" maxlength="512" :required="task.status !== 'PAUSED'" /></label>
                                <div class="form-actions full">
                                    <button v-if="task.status === 'PAUSED'" class="button" type="submit" :disabled="changing">恢复开发</button>
                                    <button v-else class="button secondary" type="submit" :disabled="changing">暂停任务</button>
                                </div>
                            </form>
                            <form v-if="isAdmin" class="review-form compact" style="margin-top:8px" @submit.prevent="cancelTask">
                                <label class="full">关闭原因（必填）<input v-model.trim="flowNote" maxlength="512" required /></label>
                                <div class="form-actions full"><button class="button danger-button" type="submit" :disabled="changing">关闭任务</button></div>
                            </form>
                        </div>
                    </div>

                    <!-- [AIREVIEW-PLAN-030] Handoff timeline. -->
                    <div v-if="task.handoffHistory?.length" class="card" style="margin-top:16px">
                        <div class="card-hd"><span class="card-ico"></span><div class="card-nm">流转历史</div></div>
                        <div class="card-bd">
                            <ul class="plan-history">
                                <li v-for="entry in task.handoffHistory" :key="entry.seq">
                                    <strong>v{{ entry.seq }} · {{ entry.fromUsername }} → {{ entry.toUsername }}</strong>
                                    <span>{{ formatChinaTime(entry.at) }}</span>
                                    <p v-if="entry.note">{{ entry.note }}</p>
                                </li>
                            </ul>
                        </div>
                    </div>
                </div>

                <div>
                    <div class="card">
                        <div class="card-hd"><span class="card-ico">▣</span><div class="card-nm">操作</div></div>
                        <div class="card-bd">
                            <div v-if="canSubmitAcceptance" class="rd-op-actions">
                                <p class="muted" style="margin:0">开发完成后提交验收，任务将进入待验收状态，由需求提出人或管理员确认。</p>
                                <button class="button" type="button" :disabled="changing" @click="submitAcceptance">提交验收</button>
                            </div>
                            <form v-else-if="canAcceptance" class="review-form compact" @submit.prevent="accept">
                                <label class="full">验收意见（可选）<textarea v-model="note" placeholder="填写验收结论或打回原因…" /></label>
                                <div class="form-actions full">
                                    <button class="button" type="submit" :disabled="changing">验收通过</button>
                                    <button class="button danger-button" type="button" :disabled="changing" @click="reject">打回修改</button>
                                </div>
                            </form>
                            <p v-else class="dash-empty" style="margin:0">
                                <template v-if="task.status === 'PENDING_ASSIGN'">任务尚未指派负责人，请管理员在任务列表中完成指派。</template>
                                <template v-else-if="task.status === 'DEVELOPING'">任务开发中，等待负责人提交验收。</template>
                                <template v-else-if="task.status === 'PAUSED'">任务暂停中，等待恢复。</template>
                                <template v-else-if="task.status === 'PENDING_ACCEPTANCE'">等待需求提出人或管理员验收。</template>
                                <template v-else-if="task.status === 'CANCELLED'">任务已关闭。</template>
                                <template v-else>任务已完成，没有可执行的操作。</template>
                            </p>
                        </div>
                    </div>
                </div>
            </div>
        </template>
        <p v-else class="empty-note">任务不存在或已被删除。</p>
    </section>
</template>
