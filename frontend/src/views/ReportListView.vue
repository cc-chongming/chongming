<script setup>
import { onMounted, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';

const reports = ref({ items: [], total: 0, page: 1, size: 20 });
const error = ref('');
const loading = ref(false);
async function load(page = 1) { loading.value = true; error.value = ''; try { reports.value = await reviewApi.listReports({ page }); } catch (requestError) { error.value = formatApiError(requestError); } finally { loading.value = false; } }
onMounted(load);
</script>

<template>
    <section class="platform-page"><header class="platform-page-header"><div><p class="eyebrow">Report</p><h1>评审报告</h1></div><button class="button secondary" type="button" :disabled="loading" @click="load">刷新</button></header>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p><p v-if="loading" class="empty-note">正在读取报告…</p>
        <ul v-else-if="reports.items.length" class="report-list"><li v-for="report in reports.items" :key="report.reviewId"><div><RouterLink :to="`/reviews/${report.reviewId}/report`"><strong>评审报告 v{{ report.reportVersion }}</strong></RouterLink><span>{{ report.reviewId }}</span></div><small>Gate v{{ report.gateVersion }} · {{ report.createdAt }}</small></li></ul><p v-else class="empty-note">尚未生成评审报告。</p><div v-if="reports.total > reports.size" class="page-actions"><button class="button secondary" :disabled="loading || reports.page <= 1" @click="load(reports.page - 1)">上一页</button><span>第 {{ reports.page }} 页，共 {{ Math.ceil(reports.total / reports.size) }} 页</span><button class="button secondary" :disabled="loading || reports.page * reports.size >= reports.total" @click="load(reports.page + 1)">下一页</button></div>
    </section>
</template>
