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

const statusMeta = {
    PENDING_ASSIGN: { tag: 'tag-pending', label: '待指派' },
    DEVELOPING: { tag: 'tag-dev', label: '开发中' },
    PENDING_ACCEPTANCE: { tag: 'tag-review', label: '待验收' },
    DONE: { tag: 'tag-done', label: '已完成' }
};
const currentStatus = computed(() => statusMeta[task.value?.status]
    ?? { tag: 'tag-draft', label: task.value?.status ?? '—' });

const currentUser = computed(() => authStore.currentUser.value);
const isAdmin = computed(() => currentUser.value?.role === 'ADMIN');
// Assignee submits acceptance while developing; ADMIN decides during pending acceptance.
const canSubmitAcceptance = computed(() => task.value?.status === 'DEVELOPING'
    && !!currentUser.value?.username && task.value?.assigneeUsername === currentUser.value.username);
const canAcceptance = computed(() => isAdmin.value && task.value?.status === 'PENDING_ACCEPTANCE');

function shortId(id) {
    return id ? `#${String(id).slice(0, 8)}` : '—';
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        task.value = await taskApi.getTask(props.taskId);
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        loading.value = false;
    }
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
                </div>

                <div>
                    <div class="card">
                        <div class="card-hd"><span class="card-ico">▣</span><div class="card-nm">操作</div></div>
                        <div class="card-bd">
                            <div v-if="canSubmitAcceptance" class="rd-op-actions">
                                <p class="muted" style="margin:0">开发完成后提交验收，任务将进入待验收状态，由管理员确认。</p>
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
                                <template v-else-if="task.status === 'PENDING_ACCEPTANCE'">等待管理员验收。</template>
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
