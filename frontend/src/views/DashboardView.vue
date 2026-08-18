<script setup>
import { computed, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { formatApiError, reviewApi } from '../api/review-api';
import { formatChinaTime } from '../services/china-time';

const dashboard = ref(null);
const error = ref('');
const loading = ref(true);

const kpis = computed(() => {
    const byStatus = dashboard.value?.requirementStatusCounts ?? {};
    const total = Object.values(byStatus).reduce((sum, n) => sum + (Number(n) || 0), 0);
    return [
        { label: '需求总数', value: total, tone: 'ac' },
        { label: '评审中', value: dashboard.value?.activeReviewCount ?? 0, tone: 'yl' },
        { label: '已通过', value: byStatus.APPROVED ?? 0, tone: 'gn' },
        { label: '已驳回', value: byStatus.REJECTED ?? 0, tone: 'rd' }
    ];
});

const stageColors = {
    PLANNING: '#2563eb', INITIAL_REVIEW: '#2563eb', CONFLICT_DETECTION: '#d97706',
    DEBATE_ROUND_1: '#d97706', DEBATE_ROUND_2: '#d97706', JUDGING: '#7c3aed',
    WAITING_HUMAN: '#d97706', COMPLETED: '#059669', FAILED: '#dc2626', CANCELLED: '#a8a29e'
};
function dotColor(stage) {
    return stageColors[stage] ?? '#a8a29e';
}

async function load() {
    loading.value = true;
    error.value = '';
    try {
        dashboard.value = await reviewApi.getDashboard();
    } catch (requestError) {
        error.value = formatApiError(requestError);
    } finally {
        loading.value = false;
    }
}

onMounted(load);
</script>

<template>
    <section class="platform-page">
        <header class="platform-page-header">
            <div>
                <p class="eyebrow">需求全生命周期</p>
                <h1>工作台概览</h1>
                <p class="muted">需求状态与评审事实独立存储，通过只读投影汇总展示。</p>
            </div>
            <button class="button secondary" type="button" :disabled="loading" @click="load">刷新</button>
        </header>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <div v-if="loading" class="loading-grid"><span /><span /><span /></div>
        <template v-else-if="dashboard">
            <div class="kpi-grid">
                <div v-for="kpi in kpis" :key="kpi.label" class="kpi" :class="`k-${kpi.tone}`">
                    <div class="k-num">{{ kpi.value }}</div>
                    <div class="k-lbl">{{ kpi.label }}</div>
                </div>
            </div>
            <div class="dash-grid">
                <section class="card">
                    <div class="card-hd"><span class="dash-ico">⚖</span><div class="card-nm">待处理评审</div><span class="tag tag-review" style="margin-left:auto">{{ dashboard.activeReviews.length }} 项</span></div>
                    <div style="padding:0">
                        <RouterLink v-for="review in dashboard.activeReviews" :key="review.reviewId" :to="`/reviews/${review.reviewId}/live`" class="feed-item clickable">
                            <span class="feed-dot" :style="{ background: dotColor(review.stage) }"></span>
                            <span class="feed-body"><span class="feed-t">{{ review.stage }}</span><span class="feed-s">{{ review.reviewId }}</span></span>
                            <span class="feed-time">尝试 #{{ review.attempt }} · {{ review.progress ?? 0 }}%</span>
                            <span class="feed-arrow">→</span>
                        </RouterLink>
                        <p v-if="!dashboard.activeReviews.length" class="dash-empty">暂无待处理评审。</p>
                    </div>
                </section>
                <section class="card">
                    <div class="card-hd"><span class="dash-ico">≣</span><div class="card-nm">最近动态</div></div>
                    <div style="padding:0">
                        <div v-for="activity in dashboard.recentActivities" :key="`${activity.reviewId}-${activity.sequence}`" class="feed-item">
                            <span class="feed-dot" style="background:#059669"></span>
                            <span class="feed-body"><span class="feed-t">{{ activity.type }}</span><span class="feed-s">{{ activity.summary }}</span></span>
                            <span class="feed-time">{{ formatChinaTime(activity.occurredAt) }}</span>
                        </div>
                        <p v-if="!dashboard.recentActivities.length" class="dash-empty">暂无评审活动。</p>
                    </div>
                </section>
            </div>
        </template>
    </section>
</template>
