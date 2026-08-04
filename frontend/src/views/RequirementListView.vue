<script setup>
import { onMounted, reactive, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';

const result = ref({ items: [], page: 1, size: 20, total: 0 });
const loading = ref(false);
const error = ref('');
const filter = reactive({ status: '', keyword: '' });

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

onMounted(() => load());
</script>

<template>
    <section class="platform-page">
        <header class="platform-page-header"><div><p class="eyebrow">Requirement</p><h1>需求列表</h1></div><RouterLink class="button" to="/requirements/create">新建需求</RouterLink></header>
        <form class="filters platform-filters" @submit.prevent="load()">
            <label>状态<select v-model="filter.status"><option value="">全部</option><option v-for="status in ['DRAFT','PENDING_REVIEW','REVIEWING','APPROVED','REJECTED','RETURNED','DEVELOPING','DONE','CANCELLED']" :key="status" :value="status">{{ status }}</option></select></label>
            <label>关键词<input v-model="filter.keyword" placeholder="标题或描述" /></label>
            <button class="button secondary" type="submit" :disabled="loading">筛选</button>
        </form>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <p v-if="loading" class="empty-note">正在读取需求…</p>
        <div v-else class="platform-table-wrap">
            <table class="platform-table"><thead><tr><th>需求</th><th>状态</th><th>优先级</th><th>仓库</th><th>更新时间</th><th>操作</th></tr></thead>
                <tbody><tr v-for="item in result.items" :key="item.id"><td><RouterLink :to="`/requirements/${item.id}`">{{ item.title }}</RouterLink></td><td><span class="status-pill">{{ item.status }}</span></td><td>{{ item.priority || '—' }}</td><td>{{ item.repositoryPath || '—' }}</td><td>{{ item.updatedAt }}</td><td><button class="text-button danger" type="button" :disabled="loading" @click="remove(item)">删除</button></td></tr></tbody>
            </table>
            <p v-if="!result.items.length" class="empty-note">没有符合条件的需求。</p>
            <div v-if="result.total > result.size" class="page-actions"><button class="button secondary" :disabled="loading || result.page <= 1" @click="load(result.page - 1)">上一页</button><span>第 {{ result.page }} 页，共 {{ Math.ceil(result.total / result.size) }} 页</span><button class="button secondary" :disabled="loading || result.page * result.size >= result.total" @click="load(result.page + 1)">下一页</button></div>
        </div>
    </section>
</template>
