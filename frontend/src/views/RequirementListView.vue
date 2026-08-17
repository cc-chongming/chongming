<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';
import { authStore } from '../stores/auth-store';

const result = ref({ items: [], page: 1, size: 20, total: 0 });
const loading = ref(false);
const error = ref('');
const filter = reactive({ status: '', keyword: '' });
const counts = ref(null);

// [AIREVIEW-PLAN-027] Creation is limited to ADMIN / manager roles; deletion is limited
// to ADMIN or the requirement creator.
const canCreate = computed(() => authStore.canCreateRequirement.value);
function canDelete(item) {
    const user = authStore.currentUser.value;
    if (!user) return false;
    return user.role === 'ADMIN' || item.creatorId === user.username;
}

const statusFilters = [
    { key: '', label: '全部' },
    { key: 'DRAFT', label: '草稿' },
    { key: 'PENDING_REVIEW', label: '待评审' },
    { key: 'REVIEWING', label: '评审中' },
    { key: 'APPROVED', label: '已通过' },
    { key: 'REJECTED', label: '已驳回' },
    { key: 'DEVELOPING', label: '开发中' }
];

const statusTag = {
    DRAFT: 'tag-draft', PENDING_REVIEW: 'tag-pending', REVIEWING: 'tag-review',
    APPROVED: 'tag-approved', REJECTED: 'tag-blocked', RETURNED: 'tag-blocked',
    DEVELOPING: 'tag-dev', DONE: 'tag-done', CANCELLED: 'tag-draft'
};
const statusLabel = {
    DRAFT: '草稿', PENDING_REVIEW: '待评审', REVIEWING: '评审中',
    APPROVED: '已通过', REJECTED: '已驳回', RETURNED: '已退回',
    DEVELOPING: '开发中', DONE: '已完成', CANCELLED: '已取消'
};
const priorityBadge = { P0: 'b-p0', P1: 'b-p1', P2: 'b-p2', P3: 'b-p2' };

const totalCount = computed(() => {
    const byStatus = counts.value?.requirementStatusCounts ?? {};
    return Object.values(byStatus).reduce((sum, n) => sum + (Number(n) || 0), 0);
});
function filterCount(key) {
    return key ? (counts.value?.requirementStatusCounts?.[key] ?? 0) : totalCount.value;
}
function selectFilter(key) {
    filter.status = key;
    load(1);
}
function shortId(id) {
    return id ? `#${String(id).slice(0, 8)}` : '—';
}

async function load(page = 1) {
    loading.value = true;
    error.value = '';
    try {
        result.value = await reviewApi.listRequirements({ ...filter, page });
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        loading.value = false;
    }
}

async function remove(item) {
    if (!globalThis.confirm(`确定删除需求“${item.title}”吗？需求会从列表移除，关联评审历史会保留。`)) return;
    loading.value = true;
    error.value = '';
    try {
        await reviewApi.deleteRequirement(item.id, item.version);
        const page = result.value.items.length === 1 && result.value.page > 1 ? result.value.page - 1 : result.value.page;
        await load(page);
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        loading.value = false;
    }
}

onMounted(async () => {
    try {
        counts.value = await reviewApi.getDashboard();
    } catch {
        counts.value = null;
    }
    load();
});
</script>

<template>
    <section class="platform-page">
        <header class="platform-page-header"><div><p class="eyebrow">Requirement</p><h1>需求库</h1></div><RouterLink v-if="canCreate" class="button" to="/requirements/create">＋ 新建需求</RouterLink></header>

        <div class="req-filter-bar">
            <button v-for="f in statusFilters" :key="f.key" type="button" class="req-filter" :class="{ active: filter.status === f.key }" @click="selectFilter(f.key)">
                {{ f.label }} <span class="req-filter-count">{{ filterCount(f.key) }}</span>
            </button>
            <div class="req-search"><span class="req-search-icon">⌕</span><input v-model="filter.keyword" type="text" placeholder="搜索需求…" @keyup.enter="load(1)" /></div>
        </div>

        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <p v-if="loading" class="empty-note">正在读取需求…</p>
        <div v-else class="platform-table-wrap">
            <table class="platform-table req-table">
                <thead><tr><th>编号</th><th>需求名称</th><th>状态</th><th>优先级</th><th>负责人</th><th>更新时间</th><th>操作</th></tr></thead>
                <tbody>
                    <tr v-for="item in result.items" :key="item.id">
                        <td class="req-id">{{ shortId(item.id) }}</td>
                        <td class="req-title"><RouterLink :to="`/requirements/${item.id}`">{{ item.title }}</RouterLink></td>
                        <td><span class="tag" :class="statusTag[item.status] ?? 'tag-draft'">{{ statusLabel[item.status] ?? item.status }}</span></td>
                        <td><span v-if="item.priority" class="badge" :class="priorityBadge[item.priority] ?? 'b-p2'">{{ item.priority }}</span><span v-else>—</span></td>
                        <td class="req-assignee">{{ item.assigneeId || '—' }}</td>
                        <td class="req-date">{{ item.updatedAt }}</td>
                        <td><button v-if="canDelete(item)" class="text-button danger" type="button" :disabled="loading" @click="remove(item)">删除</button></td>
                    </tr>
                </tbody>
            </table>
            <p v-if="!result.items.length" class="empty-note">没有符合条件的需求。</p>
            <div v-if="result.total > result.size" class="page-actions"><button class="button secondary" :disabled="loading || result.page <= 1" @click="load(result.page - 1)">上一页</button><span>第 {{ result.page }} 页，共 {{ Math.ceil(result.total / result.size) }} 页</span><button class="button secondary" :disabled="loading || result.page * result.size >= result.total" @click="load(result.page + 1)">下一页</button></div>
        </div>
    </section>
</template>
