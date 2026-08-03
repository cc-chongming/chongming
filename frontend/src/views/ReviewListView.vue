<script setup>
import { onMounted, reactive, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';

const result = ref({ items: [], total: 0, page: 1, size: 20 });
const error = ref('');
const loading = ref(false);
const filter = reactive({ stage: '', hasReport: '' });

async function load(page = 1) {
    loading.value = true;
    error.value = '';
    try {
        result.value = await reviewApi.listReviews({
            stage: filter.stage,
            hasReport: filter.hasReport === '' ? undefined : filter.hasReport === 'true', page
        });
    } catch (requestError) { error.value = formatApiError(requestError); }
    finally { loading.value = false; }
}
onMounted(load);
</script>

<template>
    <section class="platform-page"><header class="platform-page-header"><div><p class="eyebrow">Review</p><h1>评审列表</h1></div><RouterLink class="button" to="/requirements/create">发起评审</RouterLink></header>
        <form class="filters platform-filters" @submit.prevent="load"><label>阶段<select v-model="filter.stage"><option value="">全部</option><option v-for="stage in ['PENDING','SNAPSHOTTING','PLANNING','INITIAL_REVIEW','CONFLICT_DETECTION','DEBATE_ROUND_1','DEBATE_ROUND_2','JUDGING','WAITING_HUMAN','NOTIFYING','COMPLETED','CANCELLED','FAILED']" :key="stage">{{ stage }}</option></select></label><label>报告<select v-model="filter.hasReport"><option value="">全部</option><option value="true">已有报告</option><option value="false">无报告</option></select></label><button class="button secondary" :disabled="loading">筛选</button></form>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p><p v-if="loading" class="empty-note">正在读取评审…</p>
        <div v-else class="platform-table-wrap"><table class="platform-table"><thead><tr><th>评审</th><th>阶段</th><th>进度</th><th>最后事实</th><th>报告</th></tr></thead><tbody><tr v-for="item in result.items" :key="item.reviewId"><td><RouterLink :to="`/reviews/${item.reviewId}/live`">{{ item.reviewId }}</RouterLink></td><td><span class="status-pill">{{ item.stage }}</span></td><td>{{ item.progress ?? 0 }}%</td><td>{{ item.lastEventType }}</td><td>{{ item.hasReport ? `v${item.reportVersion}` : '—' }}</td></tr></tbody></table><p v-if="!result.items.length" class="empty-note">暂无评审记录。</p><div v-if="result.total > result.size" class="page-actions"><button class="button secondary" :disabled="loading || result.page <= 1" @click="load(result.page - 1)">上一页</button><span>第 {{ result.page }} 页，共 {{ Math.ceil(result.total / result.size) }} 页</span><button class="button secondary" :disabled="loading || result.page * result.size >= result.total" @click="load(result.page + 1)">下一页</button></div></div>
    </section>
</template>
