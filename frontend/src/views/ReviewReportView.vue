<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { formatApiError, reviewApi } from '../api/review-api';

const props = defineProps({ reviewId: { type: String, required: true } });
const loading = ref(false);
const generating = ref(false);
const error = ref(null);
const report = ref(null);
const markdown = ref('');
const versions = ref([]);
const selectedVersion = ref(null);
const format = ref('json');
const showRaw = ref(false);

const roleMeta = {
    PRODUCT: { icon: '💡', label: '产品' }, PROJECT: { icon: '📋', label: '项目' },
    FRONTEND: { icon: '🎨', label: '前端' }, BACKEND: { icon: '⚙', label: '后端' },
    SECURITY: { icon: '🔒', label: '安全' }, ARCHITECTURE: { icon: '🏗', label: '架构' },
    TESTING: { icon: '🧪', label: '测试' }, PERFORMANCE: { icon: '📊', label: '性能' },
    DIRECTOR: { icon: '🎯', label: '协调' }, JUDGE: { icon: '⚖', label: '裁决' },
    CONTEXT_SCOUT: { icon: '🔍', label: 'Scout' }
};
const positionMeta = { SUPPORT: { label: '支持', cls: 'pos-support' }, OPPOSE: { label: '反对', cls: 'pos-oppose' }, NEUTRAL: { label: '中性', cls: 'pos-neutral' } };
const statusMeta = { SUBMITTED: { label: '维持', cls: 'st-keep' }, UNVERIFIED: { label: '待核', cls: 'st-check' }, WITHDRAWN: { label: '撤回', cls: 'st-drop' } };
const gateMeta = {
    AI_PASS: { label: 'AI 通过', icon: '✅', tone: 'gn' }, PASS: { label: '通过', icon: '✅', tone: 'gn' },
    CONDITIONAL: { label: '有条件通过', icon: '⚠', tone: 'yl' }, RETURN: { label: '退回修改', icon: '↩', tone: 'yl' },
    BLOCK: { label: '驳回', icon: '⛔', tone: 'rd' }, HUMAN_REQUIRED: { label: '需人工裁决', icon: '🙋', tone: 'yl' },
    OVERRIDE: { label: '人工覆盖', icon: '✋', tone: 'pu' }
};
const debateStatusMap = { OPEN: '争议中', CLOSED: '已关闭', RESOLVED: '已收敛', CONVERGED: '已收敛', SETTLED: '已解决' };

const shortId = computed(() => (props.reviewId || '').slice(0, 8));
const latestVersion = computed(() => (versions.value.length ? Math.max(...versions.value.map((v) => Number(v.reportVersion))) : null));
const currentVersion = computed(() => {
    const target = selectedVersion.value ?? latestVersion.value;
    return versions.value.find((v) => Number(v.reportVersion) === target) ?? null;
});
const summary = computed(() => report.value?.summary ?? null);
const claims = computed(() => report.value?.claims ?? []);
const debates = computed(() => report.value?.debates ?? []);
const gateDecisions = computed(() => report.value?.gateDecisions ?? []);
const finalGate = computed(() => gateDecisions.value.reduce((latest, decision) => (
    !latest || Number(decision.gateVersion) > Number(latest.gateVersion) ? decision : latest
), null));

const opposeCount = computed(() => claims.value.filter((claim) => claim.position === 'OPPOSE').length);
const totalRounds = computed(() => debates.value.reduce((sum, debate) => sum + (Number(debate.currentRound) || 0), 0));
const consensus = computed(() => claims.value.length
    ? Math.round(((claims.value.length - opposeCount.value) / claims.value.length) * 100)
    : null);
const maxRound = computed(() => debates.value.reduce((max, debate) => Math.max(max, Number(debate.currentRound) || 0), 0));

function roleLabel(role) {
    const meta = roleMeta[role];
    return meta ? `${meta.icon} ${meta.label}` : (role ?? '—');
}
function severityClass(severity) {
    return `b-${String(severity || 'P2').toLowerCase()}`;
}
function gateFor(result) {
    return gateMeta[result] ?? { label: result ?? '—', icon: '•', tone: 'yl' };
}
function debateStatus(debate) {
    return debateStatusMap[debate?.status] ?? debate?.status ?? '';
}
function debateClaimCount(debate) {
    return debate?.claims?.length ?? debate?.claimIds?.length ?? 0;
}

async function load(version) {
    loading.value = true;
    error.value = null;
    try {
        const options = version != null ? { version } : {};
        const [json, text, availableVersions] = await Promise.all([
            reviewApi.getReport(props.reviewId, options)
                .catch((requestError) => (requestError.status === 404 ? null : Promise.reject(requestError))),
            reviewApi.getReport(props.reviewId, { ...options, format: 'markdown' })
                .catch((requestError) => (requestError.status === 404 ? '' : Promise.reject(requestError))),
            reviewApi.getReportVersions(props.reviewId)
        ]);
        report.value = json;
        markdown.value = text;
        versions.value = availableVersions;
        if (version != null && !availableVersions.some((v) => Number(v.reportVersion) === version)) {
            selectedVersion.value = null;
        }
    } catch (requestError) {
        error.value = requestError;
    } finally {
        loading.value = false;
    }
}

function onVersionChange() {
    load(selectedVersion.value);
}

async function generate() {
    generating.value = true;
    try {
        await reviewApi.generateReport(props.reviewId);
        await load(selectedVersion.value);
    } catch (requestError) {
        error.value = requestError;
    } finally {
        generating.value = false;
    }
}

function downloadRaw() {
    const isJson = format.value === 'json';
    const content = isJson ? JSON.stringify(report.value, null, 2) : markdown.value;
    if (!content) return;
    const blob = new Blob([content], { type: isJson ? 'application/json;charset=utf-8' : 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `review-${(props.reviewId || '').slice(0, 8)}-v${currentVersion.value?.reportVersion ?? 'latest'}.${isJson ? 'json' : 'md'}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
}

watch(() => props.reviewId, () => load(selectedVersion.value));
onMounted(() => load(selectedVersion.value));
</script>

<template>
    <section class="platform-page">
        <p v-if="error" class="error-banner" role="alert">{{ formatApiError(error) }}</p>
        <p v-if="loading && !report" class="empty-note">正在加载报告…</p>

        <template v-if="report">
            <div class="rpt-wrap">
                <div class="rpt-top">
                    <div>
                        <h1>评审报告</h1>
                        <div class="rpt-meta">
                            <span class="rpt-mono">#{{ shortId }}</span>
                            <span>· 报告 v{{ currentVersion?.reportVersion ?? '—' }}</span>
                            <span v-if="currentVersion?.createdAt">· {{ currentVersion.createdAt }}</span>
                            <span v-if="summary?.occurredAt">· 评审至 {{ summary.occurredAt }}</span>
                        </div>
                    </div>
                    <RouterLink class="button secondary" :to="{ name: 'review-workbench', params: { reviewId } }">返回工作台</RouterLink>
                </div>

                <div class="rpt-section"><h3>评审概览</h3>
                    <div class="rpt-grid">
                        <div class="rpt-kpi"><div class="num" style="color:#2563eb">{{ claims.length }}</div><div class="lbl">Claim</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#dc2626">{{ opposeCount }}</div><div class="lbl">冲突</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#d97706">{{ totalRounds }}</div><div class="lbl">辩论轮次</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#059669">{{ consensus == null ? '—' : `${consensus}%` }}</div><div class="lbl">共识度</div></div>
                    </div>
                </div>

                <div class="rpt-section"><h3>Claim 清单</h3>
                    <div class="card">
                        <table v-if="claims.length" class="rpt-table">
                            <thead><tr><th>#</th><th>角色</th><th>严重度</th><th>立场</th><th>内容</th><th>辩论后</th></tr></thead>
                            <tbody>
                                <tr v-for="(claim, index) in claims" :key="claim.claimId ?? index">
                                    <td class="rpt-mono">{{ index + 1 }}</td>
                                    <td class="rpt-nowrap">{{ roleLabel(claim.role) }}</td>
                                    <td><span class="badge" :class="severityClass(claim.severity)">{{ claim.severity }}</span></td>
                                    <td><span class="pos" :class="(positionMeta[claim.position] ?? {}).cls">{{ (positionMeta[claim.position] ?? { label: claim.position }).label }}</span></td>
                                    <td class="rpt-cell"><div class="rpt-statement">{{ claim.statement }}</div><div v-if="claim.subjectKey" class="rpt-subject">{{ claim.subjectKey }}</div></td>
                                    <td><span :class="(statusMeta[claim.status] ?? {}).cls">{{ (statusMeta[claim.status] ?? { label: claim.status ?? '—' }).label }}</span></td>
                                </tr>
                            </tbody>
                        </table>
                        <p v-else class="dash-empty">本次评审未产生公开论点。</p>
                    </div>
                </div>

                <div class="rpt-section"><h3>立场收敛</h3>
                    <div class="card"><div class="card-bd">
                        <div v-for="debate in debates" :key="debate.topicId ?? debate.subjectKey" class="wcv-row">
                            <div class="wcv-lb">{{ debate.subjectKey }}</div>
                            <div class="wcv-tr">
                                <span v-for="round in maxRound" :key="round" class="wcv-dot" :class="{ done: round <= (Number(debate.currentRound) || 0) }"></span>
                                <span class="wcv-topic">{{ debateClaimCount(debate) }} 个论点</span>
                            </div>
                            <div class="wcv-st">{{ debateStatus(debate) }}<span v-if="debate.resolution" class="wcv-res">· {{ debate.resolution }}</span></div>
                        </div>
                        <p v-if="!debates.length" class="dash-empty">本次评审未产生公开辩论。</p>
                    </div></div>
                </div>

                <div class="rpt-section"><h3>最终 Gate</h3>
                    <div v-if="finalGate" class="gate-box" :class="`t-${gateFor(finalGate.result).tone}`">
                        <div class="gate-tag" :class="`t-${gateFor(finalGate.result).tone}`">{{ gateFor(finalGate.result).icon }} {{ gateFor(finalGate.result).label }}</div>
                        <div class="gate-meta">
                            <div><strong>决策者:</strong> {{ finalGate.reviewerId ?? summary?.gate?.actor ?? '—' }} · {{ finalGate.decidedAt }}</div>
                            <div v-if="finalGate.reason"><strong>理由:</strong> {{ finalGate.reason }}</div>
                            <template v-if="finalGate.conditions?.length">
                                <div><strong>条件:</strong></div>
                                <ol class="gate-conds"><li v-for="(condition, index) in finalGate.conditions" :key="index">{{ condition }}</li></ol>
                            </template>
                            <div v-if="finalGate.overrideReason"><strong>覆盖说明:</strong> {{ finalGate.overrideReason }}</div>
                        </div>
                    </div>
                    <div v-else class="gate-box t-yl">
                        <div class="gate-tag t-yl">⏳ 待定</div>
                        <div class="gate-meta">本轮评审尚未形成最终 Gate 决策。</div>
                    </div>
                </div>

                <div class="rpt-section"><h3>版本化公开输出</h3>
                    <div class="card"><div class="card-bd">
                        <div class="rpt-raw-bar">
                            <label>版本<select v-model="selectedVersion" @change="onVersionChange">
                                <option :value="null">最新 (v{{ latestVersion ?? '—' }})</option>
                                <option v-for="v in versions" :key="v.reportVersion" :value="Number(v.reportVersion)">v{{ v.reportVersion }} · {{ v.createdAt }}</option>
                            </select></label>
                            <label>格式<select v-model="format"><option value="json">结构化 JSON</option><option value="markdown">Markdown 原文</option></select></label>
                            <button class="button secondary" type="button" :disabled="generating" @click="generate">{{ generating ? '正在生成…' : '生成新版本' }}</button>
                            <span class="rpt-versions">共 {{ versions.length }} 个版本</span>
                        </div>
                        <div class="rpt-raw-row">
                            <div class="rpt-raw-toggle" @click="showRaw = !showRaw">{{ showRaw ? '收起原文' : '查看原文' }}<span class="rpt-chev" aria-hidden="true">{{ showRaw ? '▴' : '▾' }}</span></div>
                            <button v-if="showRaw" class="button secondary sm" type="button" @click="downloadRaw">下载 {{ format === 'json' ? 'JSON' : 'MD' }}</button>
                        </div>
                        <pre v-if="showRaw" class="report-content"><code>{{ format === 'json' ? JSON.stringify(report, null, 2) : markdown }}</code></pre>
                        <p v-if="showRaw" class="muted" style="margin-top:6px">Markdown 以纯文本呈现，不会注入或执行 HTML。</p>
                    </div></div>
                </div>
            </div>
        </template>

        <section v-else-if="!loading" class="panel empty-report">
            <h2>尚无报告</h2>
            <p>报告通常在最终 Gate 后自动生成；若异步生成失败，可在这里请求新版本。</p>
            <button class="button" type="button" :disabled="generating" @click="generate">{{ generating ? '正在生成…' : '生成报告' }}</button>
        </section>
    </section>
</template>
