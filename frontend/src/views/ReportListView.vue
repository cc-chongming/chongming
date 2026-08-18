<script setup>
import { onMounted, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';
import { formatChinaTime } from '../services/china-time';

const reports = ref({ items: [], total: 0, page: 1, size: 20 });
const error = ref('');
const loading = ref(false);
function shortId(id) { return id ? `#${String(id).slice(0, 8)}` : '—'; }
async function load(page = 1) { loading.value = true; error.value = ''; try { reports.value = await reviewApi.listReports({ page }); } catch (requestError) { error.value = formatApiError(requestError); } finally { loading.value = false; } }
onMounted(load);
</script>

<template>
    <section class="platform-page"><header class="platform-page-header"><div><p class="eyebrow">Report</p><h1>评审报告</h1></div><button class="button secondary" type="button" :disabled="loading" @click="load">刷新</button></header>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <p v-if="loading" class="empty-note">正在读取报告…</p>
        <div v-else class="rv-list">
            <div v-for="report in reports.items" :key="report.reviewId" class="rv-card">
                <RouterLink class="rv-link" :to="`/reviews/${report.reviewId}/report`">
                    <div class="rv-top"><span class="rv-ico">↗</span><div class="rv-title">评审报告 v{{ report.reportVersion }}</div><span class="tag tag-done">已完成</span></div>
                    <div class="rv-body">{{ shortId(report.reviewId) }} · {{ report.reviewId }}</div>
                    <div class="rv-footer">
                        <span class="rv-fi">⚖ Gate v{{ report.gateVersion }}</span>
                        <span class="rv-fi" style="margin-left:auto">{{ formatChinaTime(report.createdAt) }}</span>
                    </div>
                </RouterLink>
            </div>
            <p v-if="!reports.items.length" class="empty-note">尚未生成评审报告。</p>
            <div v-if="reports.total > reports.size" class="page-actions"><button class="button secondary" :disabled="loading || reports.page <= 1" @click="load(reports.page - 1)">上一页</button><span>第 {{ reports.page }} 页，共 {{ Math.ceil(reports.total / reports.size) }} 页</span><button class="button secondary" :disabled="loading || reports.page * reports.size >= reports.total" @click="load(reports.page + 1)">下一页</button></div>
        </div>
    </section>
</template>
