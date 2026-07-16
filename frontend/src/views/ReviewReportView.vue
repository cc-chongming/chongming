<script setup>
import { onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { formatApiError, reviewApi } from '../api/review-api';

const props = defineProps({ reviewId: { type: String, required: true } });
const loading = ref(false);
const generating = ref(false);
const error = ref(null);
const report = ref(null);
const markdown = ref('');
const versions = ref([]);
const format = ref('json');

async function load() {
    loading.value = true;
    error.value = null;
    try {
        const [json, text, availableVersions] = await Promise.all([
            reviewApi.getReport(props.reviewId).catch((requestError) => requestError.status === 404 ? null : Promise.reject(requestError)),
            reviewApi.getReport(props.reviewId, { format: 'markdown' }).catch((requestError) => requestError.status === 404 ? '' : Promise.reject(requestError)),
            reviewApi.getReportVersions(props.reviewId)
        ]);
        report.value = json;
        markdown.value = text;
        versions.value = availableVersions;
    } catch (requestError) {
        error.value = requestError;
    } finally {
        loading.value = false;
    }
}

async function generate() {
    generating.value = true;
    try {
        await reviewApi.generateReport(props.reviewId);
        await load();
    } catch (requestError) {
        error.value = requestError;
    } finally {
        generating.value = false;
    }
}

watch(() => props.reviewId, load);
onMounted(load);
</script>

<template>
    <section class="report-page">
        <header class="workbench-header"><div><p class="eyebrow">版本化公开输出</p><h1>评审报告</h1></div><RouterLink class="button secondary" :to="{ name: 'review-workbench', params: { reviewId } }">返回工作台</RouterLink></header>
        <p v-if="error" class="error-banner" role="alert">{{ formatApiError(error) }}</p>
        <p v-if="loading" class="empty-note">正在加载报告…</p>
        <template v-else-if="report">
            <div class="report-toolbar"><label>显示格式<select v-model="format"><option value="json">结构化 JSON</option><option value="markdown">Markdown 原文</option></select></label><span>已有 {{ versions.length }} 个报告版本</span></div>
            <pre v-if="format === 'json'" class="report-content"><code>{{ JSON.stringify(report, null, 2) }}</code></pre>
            <pre v-else class="report-content"><code>{{ markdown }}</code></pre>
            <p class="muted">Markdown 以纯文本呈现，不会注入或执行 HTML。</p>
        </template>
        <section v-else class="panel empty-report"><h2>尚无报告</h2><p>报告通常在最终 Gate 后自动生成；若异步生成失败，可在这里请求新版本。</p><button class="button" type="button" :disabled="generating" @click="generate">{{ generating ? '正在生成…' : '生成报告' }}</button></section>
    </section>
</template>
