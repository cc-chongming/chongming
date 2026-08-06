<script setup>
import { onMounted, reactive, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';

const result = ref({ items: [], total: 0, page: 1, size: 20 });
const error = ref('');
const loading = ref(false);
const filter = reactive({ stage: '', hasReport: '' });

const stages = ['PENDING', 'SNAPSHOTTING', 'PLANNING', 'INITIAL_REVIEW', 'CONFLICT_DETECTION', 'DEBATE_ROUND_1', 'DEBATE_ROUND_2', 'JUDGING', 'WAITING_HUMAN', 'NOTIFYING', 'COMPLETED', 'CANCELLED', 'FAILED'];
const stageTag = {
    PENDING: 'tag-draft', SNAPSHOTTING: 'tag-pending', PLANNING: 'tag-pending',
    INITIAL_REVIEW: 'tag-review', CONFLICT_DETECTION: 'tag-review',
    DEBATE_ROUND_1: 'tag-review', DEBATE_ROUND_2: 'tag-review',
    JUDGING: 'tag-dev', WAITING_HUMAN: 'tag-pending', NOTIFYING: 'tag-pending',
    COMPLETED: 'tag-done', CANCELLED: 'tag-draft', FAILED: 'tag-blocked'
};
const stageLabel = {
    PENDING: '待处理', SNAPSHOTTING: '快照中', PLANNING: '规划中',
    INITIAL_REVIEW: '初审中', CONFLICT_DETECTION: '冲突检测',
    DEBATE_ROUND_1: '辩论 R1', DEBATE_ROUND_2: '辩论 R2',
    JUDGING: '裁决中', WAITING_HUMAN: '待人工', NOTIFYING: '通知中',
    COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '已失败'
};
function shortId(id) { return id ? `#${String(id).slice(0, 8)}` : '—'; }
function progressColor(stage) {
    if (stage === 'COMPLETED') return '#059669';
    if (stage === 'FAILED') return '#dc2626';
    if (stage === 'CANCELLED') return '#a8a29e';
    if (stage === 'DEBATE_ROUND_1' || stage === 'DEBATE_ROUND_2' || stage === 'WAITING_HUMAN') return '#d97706';
    if (stage === 'JUDGING') return '#7c3aed';
    return '#2563eb';
}

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
        <form class="filters platform-filters rv-filter-bar" @submit.prevent="load">
            <label>阶段<select v-model="filter.stage"><option value="">全部</option><option v-for="stage in stages" :key="stage">{{ stage }}</option></select></label>
            <label>报告<select v-model="filter.hasReport"><option value="">全部</option><option value="true">已有报告</option><option value="false">无报告</option></select></label>
            <button class="button secondary" type="submit" :disabled="loading">筛选</button>
        </form>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <p v-if="loading" class="empty-note">正在读取评审…</p>
        <div v-else class="rv-list">
            <div v-for="item in result.items" :key="item.reviewId" class="rv-card">
                <RouterLink class="rv-link" :to="`/reviews/${item.reviewId}/live`">
                    <div class="rv-top"><span class="rv-ico">⚖</span><div class="rv-title">{{ shortId(item.reviewId) }}</div><span class="tag" :class="stageTag[item.stage] ?? 'tag-draft'">{{ stageLabel[item.stage] ?? item.stage }}</span></div>
                    <div class="rv-body">最后事实: {{ item.lastEventType ?? '—' }} · 尝试 #{{ item.attempt }}</div>
                    <div class="rv-footer">
                        <span class="rv-fi">📋 {{ item.reviewId }}</span>
                        <span class="rv-fi">📄 {{ item.hasReport ? `报告 v${item.reportVersion}` : '暂无报告' }}</span>
                        <span class="rv-fi" style="margin-left:auto">{{ item.occurredAt ?? '—' }}</span>
                    </div>
                    <div class="rv-progress"><div class="bar" :style="{ width: `${item.progress ?? 0}%`, background: progressColor(item.stage) }"></div></div>
                </RouterLink>
            </div>
            <p v-if="!result.items.length" class="empty-note">暂无评审记录。</p>
            <div v-if="result.total > result.size" class="page-actions"><button class="button secondary" :disabled="loading || result.page <= 1" @click="load(result.page - 1)">上一页</button><span>第 {{ result.page }} 页，共 {{ Math.ceil(result.total / result.size) }} 页</span><button class="button secondary" :disabled="loading || result.page * result.size >= result.total" @click="load(result.page + 1)">下一页</button></div>
        </div>
    </section>
</template>
