<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { formatApiError, ReviewApiError } from '../api/review-api';
import { taskApi } from '../api/task-api';
import { authApi } from '../api/auth-api';
import { authStore } from '../stores/auth-store';
import { formatChinaTime } from '../services/china-time';

/**
 * Task-center list shared by the `/tasks` (all) and `/tasks/mine` (mine) routes.
 * Follows the RequirementListView filter + table + pagination paradigm.
 */
const props = defineProps({ scope: { type: String, default: 'all' } });
const router = useRouter();

const result = ref({ items: [], page: 1, size: 20, total: 0 });
const loading = ref(false);
const error = ref('');
const filter = reactive({ status: '' });

const users = ref([]);
const usersLoaded = ref(false);
const assigning = ref(false);
const assignTarget = ref(null);
const assignUsername = ref('');

const isAdmin = computed(() => authStore.currentUser.value?.role === 'ADMIN');
const isMine = computed(() => props.scope === 'mine');
const pageTitle = computed(() => (isMine.value ? '我的任务' : '全部任务'));

const statusFilters = [
    { key: '', label: '全部' },
    { key: 'PENDING_ASSIGN', label: '待指派' },
    { key: 'DEVELOPING', label: '开发中' },
    { key: 'PENDING_ACCEPTANCE', label: '待验收' },
    { key: 'DONE', label: '已完成' }
];
const statusMeta = {
    PENDING_ASSIGN: { tag: 'tag-pending', label: '待指派' },
    DEVELOPING: { tag: 'tag-dev', label: '开发中' },
    PENDING_ACCEPTANCE: { tag: 'tag-review', label: '待验收' },
    DONE: { tag: 'tag-done', label: '已完成' }
};

function metaOf(status) {
    return statusMeta[status] ?? { tag: 'tag-draft', label: status ?? '—' };
}
function selectFilter(key) {
    filter.status = key;
    load(1);
}

async function load(page = 1) {
    loading.value = true;
    error.value = '';
    try {
        const params = { ...filter, page };
        if (isMine.value) params.mine = true;
        result.value = await taskApi.listTasks(params);
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        loading.value = false;
    }
}

async function openAssign(task) {
    assignTarget.value = task;
    assignUsername.value = task.assigneeUsername ?? '';
    error.value = '';
    if (!usersLoaded.value) {
        try {
            users.value = await authApi.listUsers();
            usersLoaded.value = true;
        } catch (requestError) {
            error.value = formatApiError(requestError);
        }
    }
}

function closeAssign() {
    assignTarget.value = null;
    assignUsername.value = '';
}

async function submitAssign() {
    const target = assignTarget.value;
    if (!target || !assignUsername.value) return;
    assigning.value = true;
    error.value = '';
    try {
        await taskApi.assign(target.taskId, {
            assigneeUsername: assignUsername.value,
            expectedVersion: target.version
        });
        closeAssign();
        await load(result.value.page);
    } catch (requestError) {
        if (requestError instanceof ReviewApiError && requestError.errorCode === 'VERSION_CONFLICT') {
            await load(result.value.page);
            error.value = '任务版本已刷新，请核对最新状态后重新指派。';
        } else {
            error.value = formatApiError(requestError);
        }
    } finally {
        assigning.value = false;
    }
}

function openDetail(task) {
    router.push(`/tasks/${task.taskId}`);
}
function onRowClick(event, task) {
    if (event.target.closest('a,button,select,input')) return;
    openDetail(task);
}

watch(() => props.scope, () => {
    filter.status = '';
    closeAssign();
    load(1);
});

onMounted(() => load());
</script>

<template>
    <section class="platform-page">
        <header class="platform-page-header">
            <div><p class="eyebrow">Task Center</p><h1>{{ pageTitle }}</h1></div>
            <RouterLink class="button secondary" :to="isMine ? '/tasks' : '/tasks/mine'">{{ isMine ? '查看全部任务' : '查看我的任务' }}</RouterLink>
        </header>

        <div class="req-filter-bar">
            <button v-for="f in statusFilters" :key="f.key" type="button" class="req-filter" :class="{ active: filter.status === f.key }" @click="selectFilter(f.key)">
                {{ f.label }}
            </button>
        </div>

        <section v-if="assignTarget" class="panel" aria-labelledby="task-assign-title" style="margin-bottom:16px">
            <h2 id="task-assign-title">指派任务</h2>
            <p class="muted">为任务“{{ assignTarget.title }}”选择负责人，提交后任务进入开发中。</p>
            <form class="review-form compact" @submit.prevent="submitAssign">
                <label>负责人
                    <select v-model="assignUsername" required>
                        <option value="" disabled>请选择…</option>
                        <option v-for="user in users" :key="user.username" :value="user.username">
                            {{ user.displayName || user.username }}（{{ user.username }}{{ user.role === 'ADMIN' ? ' · 管理员' : '' }}）
                        </option>
                    </select>
                </label>
                <div class="form-actions full">
                    <button class="button" type="submit" :disabled="assigning || !assignUsername">{{ assigning ? '正在指派…' : '确认指派' }}</button>
                    <button class="button secondary" type="button" :disabled="assigning" @click="closeAssign">取消</button>
                </div>
            </form>
        </section>

        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <p v-if="loading" class="empty-note">正在读取任务…</p>
        <div v-else class="platform-table-wrap">
            <table class="platform-table req-table">
                <thead><tr><th>任务标题</th><th>关联需求</th><th>状态</th><th>负责人</th><th>指派人</th><th>更新时间</th><th v-if="isAdmin">操作</th></tr></thead>
                <tbody>
                    <tr v-for="item in result.items" :key="item.taskId" class="task-row" @click="onRowClick($event, item)">
                        <td class="req-title"><RouterLink :to="`/tasks/${item.taskId}`">{{ item.title }}</RouterLink></td>
                        <td><RouterLink v-if="item.requirementId" :to="`/requirements/${item.requirementId}`">{{ item.requirementTitle || '查看需求' }}</RouterLink><span v-else>—</span></td>
                        <td><span class="tag" :class="metaOf(item.status).tag">{{ metaOf(item.status).label }}</span></td>
                        <td class="req-assignee">{{ item.assigneeDisplayName || item.assigneeUsername || '—' }}</td>
                        <td class="req-assignee">{{ item.dispatcherUsername || '—' }}</td>
                        <td class="req-date">{{ formatChinaTime(item.updatedAt) }}</td>
                        <td v-if="isAdmin">
                            <button v-if="item.status === 'PENDING_ASSIGN'" class="button secondary sm" type="button" :disabled="loading" @click="openAssign(item)">指派</button>
                            <span v-else>—</span>
                        </td>
                    </tr>
                </tbody>
            </table>
            <p v-if="!result.items.length" class="empty-note">{{ isMine ? '当前没有与我相关的任务。' : '没有符合条件的任务。' }}</p>
            <div v-if="result.total > result.size" class="page-actions"><button class="button secondary" :disabled="loading || result.page <= 1" @click="load(result.page - 1)">上一页</button><span>第 {{ result.page }} 页，共 {{ Math.ceil(result.total / result.size) }} 页</span><button class="button secondary" :disabled="loading || result.page * result.size >= result.total" @click="load(result.page + 1)">下一页</button></div>
        </div>
    </section>
</template>

<style scoped>
/* Rows navigate on click; keep the pointer affordance without touching shared styles. */
.task-row { cursor: pointer; }
</style>
