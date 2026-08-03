<script setup>
import { onMounted, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';

const dashboard = ref(null);
const error = ref('');
const loading = ref(true);

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
            <div class="metric-grid">
                <article class="metric-card"><span>待评审需求</span><strong>{{ dashboard.pendingRequirementCount }}</strong></article>
                <article class="metric-card"><span>活跃评审</span><strong>{{ dashboard.activeReviewCount }}</strong></article>
                <article v-for="(count, status) in dashboard.requirementStatusCounts" :key="status" class="metric-card compact">
                    <span>{{ status }}</span><strong>{{ count }}</strong>
                </article>
            </div>
            <div class="platform-grid">
                <section class="panel">
                    <div class="panel-heading"><h2>进行中的评审</h2><RouterLink class="text-button" to="/reviews">全部评审</RouterLink></div>
                    <ul v-if="dashboard.activeReviews.length" class="platform-list">
                        <li v-for="review in dashboard.activeReviews" :key="review.reviewId">
                            <RouterLink :to="`/reviews/${review.reviewId}/live`"><strong>{{ review.stage }}</strong><span>{{ review.reviewId }}</span></RouterLink>
                            <small>尝试 #{{ review.attempt }} · {{ review.progress ?? 0 }}%</small>
                        </li>
                    </ul>
                    <p v-else class="empty-note">暂时没有活跃的评审。</p>
                </section>
                <section class="panel">
                    <div class="panel-heading"><h2>最近活动</h2></div>
                    <ol v-if="dashboard.recentActivities.length" class="platform-list activity-list">
                        <li v-for="activity in dashboard.recentActivities" :key="`${activity.reviewId}-${activity.sequence}`">
                            <strong>{{ activity.type }}</strong><p>{{ activity.summary }}</p><small>{{ activity.occurredAt }}</small>
                        </li>
                    </ol>
                    <p v-else class="empty-note">暂无评审活动。</p>
                </section>
            </div>
        </template>
    </section>
</template>
