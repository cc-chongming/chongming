<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { formatApiError, reviewApi } from '../api/review-api';
import { latestGateDecision, presentDebateJudgement } from '../services/review-conclusion-presenter';
import { formatChinaTime } from '../services/china-time';

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
const finalGate = computed(() => latestGateDecision(gateDecisions.value));
// [AIREVIEW-PLAN-023#6.3] A final report must preserve every debate's readable Judge outcome.
const debateConclusions = computed(() => debates.value.map((debate) => ({
    debate,
    judgement: presentDebateJudgement(debate, claims.value)
})));

const opposeCount = computed(() => claims.value.filter((claim) => claim.position === 'OPPOSE').length);
const totalRounds = computed(() => debates.value.reduce((sum, debate) => sum + (Number(debate.currentRound) || 0), 0));
const consensus = computed(() => claims.value.length
    ? Math.round(((claims.value.length - opposeCount.value) / claims.value.length) * 100)
    : null);
const maxRound = computed(() => debates.value.reduce((max, debate) => Math.max(max, Number(debate.currentRound) || 0), 0));

// [AIREVIEW-PLAN-024#方案5] Five checkpoint conclusion sections. Counters come straight from the
// server-side projection; entries keep the deterministic role + checkpointKey order.
const assessmentReport = computed(() => (
    report.value && typeof report.value.assessments === 'object' && report.value.assessments !== null
        ? report.value.assessments
        : null
));
const assessmentSections = computed(() => {
    const view = assessmentReport.value;
    if (!view) return [];
    const byRoleAndKey = (left, right) => String(left.role ?? '').localeCompare(String(right.role ?? ''))
        || String(left.checkpointKey ?? '').localeCompare(String(right.checkpointKey ?? ''));
    const sortEntries = (entries) => (Array.isArray(entries) ? [...entries].sort(byRoleAndKey) : []);
    return [
        { key: 'confirmed', title: '确定结论', tone: 'asmt-confirmed', count: view.confirmed ?? 0, hint: '证据充分，检查点符合或无问题', entries: sortEntries(view.confirmedEntries) },
        { key: 'partial', title: '部分满足', tone: 'asmt-partial', count: view.partial ?? 0, hint: '部分符合，未满足部分已说明', entries: sortEntries(view.partialEntries) },
        { key: 'gap', title: '风险缺口', tone: 'asmt-gap', count: view.gap ?? 0, hint: '确认存在缺口，需要处置', entries: sortEntries(view.gapEntries) },
        { key: 'unknown', title: '证据不足', tone: 'asmt-unknown', count: view.unknown ?? 0, hint: '授权证据不足以确认，“未看到”不等于“未实现”', entries: sortEntries(view.unknownEntries) },
        { key: 'notApplicable', title: '不适用', tone: 'asmt-not-applicable', count: view.notApplicable ?? 0, hint: '检查点对本次需求不适用', entries: sortEntries(view.notApplicableEntries) }
    ];
});

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
                            <span v-if="currentVersion?.createdAt">· {{ formatChinaTime(currentVersion.createdAt) }}</span>
                            <span v-if="summary?.occurredAt">· 评审至 {{ formatChinaTime(summary.occurredAt) }}</span>
                        </div>
                    </div>
                    <RouterLink class="button secondary" :to="{ name: 'review-live', params: { reviewId } }">返回实时观察台</RouterLink>
                </div>

                <div class="rpt-section"><h3>评审概览</h3>
                    <div class="rpt-grid">
                        <div class="rpt-kpi"><div class="num" style="color:#2563eb">{{ claims.length }}</div><div class="lbl">Claim</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#dc2626">{{ opposeCount }}</div><div class="lbl">冲突</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#d97706">{{ totalRounds }}</div><div class="lbl">辩论轮次</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#059669">{{ consensus == null ? '—' : `${consensus}%` }}</div><div class="lbl">共识度</div></div>
                    </div>
                </div>

                <div v-if="assessmentReport" class="rpt-section"><h3>检查点结论</h3>
                    <div class="rpt-grid asmt-kpis">
                        <div class="rpt-kpi"><div class="num" style="color:#44403c">{{ assessmentReport.required ?? 0 }}</div><div class="lbl">必检检查点</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#2563eb">{{ assessmentReport.covered ?? 0 }}</div><div class="lbl">已覆盖</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#16a34a">{{ assessmentReport.confirmed ?? 0 }}</div><div class="lbl">确定结论</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#d97706">{{ assessmentReport.partial ?? 0 }}</div><div class="lbl">部分满足</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#dc2626">{{ assessmentReport.gap ?? 0 }}</div><div class="lbl">风险缺口</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#7c3aed">{{ assessmentReport.unknown ?? 0 }}</div><div class="lbl">证据不足</div></div>
                        <div class="rpt-kpi"><div class="num" style="color:#78716c">{{ assessmentReport.notApplicable ?? 0 }}</div><div class="lbl">不适用</div></div>
                    </div>
                    <p v-if="assessmentReport.uncoveredCheckpoints?.length" class="asmt-uncovered">
                        <strong>未执行（{{ assessmentReport.uncoveredCheckpoints.length }}）：</strong>
                        <code v-for="slot in assessmentReport.uncoveredCheckpoints" :key="slot">{{ slot }}</code>
                    </p>
                    <div class="asmt-columns">
                        <section v-for="section in assessmentSections" :key="section.key" class="asmt-block" :class="section.tone" :aria-label="`${section.title} ${section.count} 项`">
                            <h4>{{ section.title }} {{ section.count }}</h4>
                            <p class="asmt-hint">{{ section.hint }}</p>
                            <ol v-if="section.entries.length">
                                <li v-for="(entry, index) in section.entries" :key="`${entry.role}-${entry.checkpointKey}-${index}`">
                                    <div class="asmt-entry-head"><strong>{{ roleLabel(entry.role) }}</strong><code>{{ entry.checkpointKey }}</code></div>
                                    <p v-if="entry.summary" class="asmt-summary">{{ entry.summary }}</p>
                                    <p v-if="entry.reasonSummary" class="asmt-reason">{{ entry.reasonSummary }}</p>
                                    <span v-if="entry.evidenceIds?.length" class="asmt-evidence">证据 {{ entry.evidenceIds.length }} 项</span>
                                </li>
                            </ol>
                            <p v-else class="dash-empty">无</p>
                        </section>
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

                <div class="rpt-section"><h3>辩论与 Judge 裁决</h3>
                    <div class="card"><div class="card-bd">
                        <article v-for="entry in debateConclusions" :key="entry.debate.topicId ?? entry.debate.subjectKey" class="wcv-item report-debate">
                            <div class="wcv-row">
                                <div class="wcv-lb">{{ entry.debate.subjectKey }}</div>
                                <div class="wcv-tr">
                                    <span v-for="round in maxRound" :key="round" class="wcv-dot" :class="{ done: round <= (Number(entry.debate.currentRound) || 0) }"></span>
                                    <span class="wcv-topic">{{ debateClaimCount(entry.debate) }} 个论点</span>
                                    <span class="wcv-st">{{ debateStatus(entry.debate) }}</span>
                                </div>
                            </div>
                            <p v-if="entry.debate.resolution" class="wcv-res-line">{{ entry.debate.resolution }}</p>

                            <section v-if="entry.judgement" class="report-judge-result" :aria-label="`${entry.debate.subjectKey} 的 Judge 裁决`">
                                <header>
                                    <div><span class="judge-label">Judge 结论</span><strong>{{ entry.judgement.resultLabel }}</strong></div>
                                    <time v-if="entry.judgement.createdAt">{{ formatChinaTime(entry.judgement.createdAt) }}</time>
                                </header>
                                <p class="judge-reason"><strong>裁决理由：</strong>{{ entry.judgement.reason }}</p>
                                <div class="judge-claim-columns">
                                    <section class="judge-claim-group accepted">
                                        <h4>采信 {{ entry.judgement.accepted.length }} 项</h4>
                                        <ol v-if="entry.judgement.accepted.length">
                                            <li v-for="claim in entry.judgement.accepted" :key="claim.claimId">
                                                <template v-if="!claim.missing">
                                                    <strong>{{ claim.statement || '该 Claim 暂无公开正文' }}</strong>
                                                    <p v-if="claim.reasonSummary">{{ claim.reasonSummary }}</p>
                                                    <code v-if="claim.subjectKey">{{ claim.subjectKey }}</code>
                                                </template>
                                                <span v-else>Claim {{ claim.claimId }}（当前报告未包含详情）</span>
                                            </li>
                                        </ol>
                                        <p v-else class="muted">Judge 未列出采信 Claim。</p>
                                    </section>
                                    <section class="judge-claim-group rejected">
                                        <h4>拒绝 {{ entry.judgement.rejected.length }} 项</h4>
                                        <ol v-if="entry.judgement.rejected.length">
                                            <li v-for="claim in entry.judgement.rejected" :key="claim.claimId">
                                                <template v-if="!claim.missing">
                                                    <strong>{{ claim.statement || '该 Claim 暂无公开正文' }}</strong>
                                                    <p v-if="claim.reasonSummary">{{ claim.reasonSummary }}</p>
                                                    <code v-if="claim.subjectKey">{{ claim.subjectKey }}</code>
                                                </template>
                                                <span v-else>Claim {{ claim.claimId }}（当前报告未包含详情）</span>
                                            </li>
                                        </ol>
                                        <p v-else class="muted">Judge 未列出拒绝 Claim。</p>
                                    </section>
                                </div>
                            </section>
                            <p v-else class="report-judge-empty">该议题尚未形成 Judge 裁决。</p>
                        </article>
                        <p v-if="!debates.length" class="dash-empty">本次评审未产生公开辩论。</p>
                    </div></div>
                </div>

                <div class="rpt-section"><h3>最终 Gate</h3>
                    <div v-if="finalGate" class="gate-box" :class="`t-${gateFor(finalGate.result).tone}`">
                        <div class="gate-tag" :class="`t-${gateFor(finalGate.result).tone}`">{{ gateFor(finalGate.result).icon }} {{ gateFor(finalGate.result).label }}</div>
                        <div class="gate-meta">
                            <div><strong>决策者:</strong> {{ finalGate.reviewerId ?? summary?.gate?.actor ?? '—' }} · {{ formatChinaTime(finalGate.decidedAt) }}</div>
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
                                <option v-for="v in versions" :key="v.reportVersion" :value="Number(v.reportVersion)">v{{ v.reportVersion }} · {{ formatChinaTime(v.createdAt) }}</option>
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

<style scoped>
.report-debate { display: grid; gap: 12px; padding: 14px 0; }
.report-debate + .report-debate { border-top: 1px solid #e7e5e4; }
.report-judge-result { padding: 14px; border: 1px solid #d6d3d1; border-radius: 10px; background: #fafaf9; }
.report-judge-result > header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.report-judge-result > header div { display: flex; align-items: center; gap: 8px; }
.report-judge-result time { color: #78716c; font-size: 12px; }
.judge-label { padding: 3px 7px; color: #1d4ed8; background: #dbeafe; border-radius: 999px; font-size: 11px; font-weight: 800; }
.judge-reason { margin: 11px 0; line-height: 1.65; }
.judge-claim-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.judge-claim-group { padding: 11px 12px; border: 1px solid #e7e5e4; border-radius: 8px; background: #fff; }
.judge-claim-group.accepted { border-top: 3px solid #16a34a; }
.judge-claim-group.rejected { border-top: 3px solid #dc2626; }
.judge-claim-group h4 { margin: 0 0 8px; font-size: 13px; }
.judge-claim-group ol { display: grid; gap: 8px; margin: 0; padding-left: 20px; }
.judge-claim-group li { padding-left: 2px; line-height: 1.5; }
.judge-claim-group li strong { display: block; font-size: 13px; }
.judge-claim-group li p { margin: 3px 0; color: #57534e; font-size: 12px; }
.judge-claim-group li code { color: #78716c; font-size: 11px; overflow-wrap: anywhere; }
.report-judge-empty { margin: 0; padding: 10px 12px; color: #92400e; background: #fffbeb; border-radius: 8px; }
.asmt-kpis { grid-template-columns: repeat(auto-fit, minmax(96px, 1fr)); }
.asmt-uncovered { margin: 10px 0; padding: 10px 12px; color: #57534e; background: #f5f5f4; border-radius: 8px; font-size: 13px; }
.asmt-uncovered code { display: inline-block; margin: 2px 6px 0 0; padding: 1px 6px; color: #44403c; background: #fff; border: 1px solid #e7e5e4; border-radius: 6px; font-size: 11px; overflow-wrap: anywhere; }
.asmt-columns { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; margin-top: 12px; }
.asmt-block { padding: 12px; border: 1px solid #e7e5e4; border-radius: 10px; background: #fff; min-width: 0; }
.asmt-block h4 { margin: 0; font-size: 14px; }
.asmt-hint { margin: 4px 0 10px; color: #78716c; font-size: 12px; }
.asmt-block ol { display: grid; gap: 10px; margin: 0; padding-left: 18px; }
.asmt-block li { line-height: 1.5; min-width: 0; }
.asmt-entry-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 13px; }
.asmt-entry-head code { color: #78716c; font-size: 11px; overflow-wrap: anywhere; }
.asmt-summary { margin: 3px 0; font-size: 13px; overflow-wrap: anywhere; }
.asmt-reason { margin: 0; color: #57534e; font-size: 12px; overflow-wrap: anywhere; }
.asmt-evidence { display: inline-block; margin-top: 4px; padding: 1px 7px; color: #1d4ed8; background: #dbeafe; border-radius: 999px; font-size: 11px; font-weight: 700; }
.asmt-block.asmt-confirmed { border-top: 3px solid #16a34a; }
.asmt-block.asmt-partial { border-top: 3px solid #d97706; }
.asmt-block.asmt-gap { border-top: 3px solid #dc2626; }
.asmt-block.asmt-unknown { border-top: 3px solid #7c3aed; }
.asmt-block.asmt-not-applicable { border-top: 3px solid #a8a29e; }
@media (max-width: 760px) {
    .judge-claim-columns { grid-template-columns: 1fr; }
    .report-judge-result > header { align-items: flex-start; flex-direction: column; }
}
</style>
