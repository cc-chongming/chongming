<script setup>
// [AIREVIEW-PLAN-023#5.2] Stable, readable rendering of the persisted Scout conclusion.
import { computed } from 'vue';
import SafeMarkdown from './SafeMarkdown.vue';

const props = defineProps({ conclusion: { type: Object, default: null } });
const summary = computed(() => props.conclusion?.summary ?? props.conclusion?.publicSummary ?? '');
const moduleRoots = computed(() => props.conclusion?.moduleRoots ?? []);
const entryPoints = computed(() => props.conclusion?.entryPoints ?? []);
const constraints = computed(() => props.conclusion?.constraints ?? []);
const risks = computed(() => props.conclusion?.risks ?? []);
const evidencePaths = computed(() => props.conclusion?.evidencePaths ?? []);
const roleScopes = computed(() => {
    const value = props.conclusion?.roleScopes;
    if (Array.isArray(value)) return value;
    if (value && typeof value === 'object') return Object.entries(value).map(([role, scope]) => ({ role, scope }));
    return [];
});
// The raw Scout output embeds untrusted tool envelopes (huge grep dumps) and repeated lines;
// collapse them so the expandable full context stays readable.
const cleanedRaw = computed(() => sanitizeScoutRaw(props.conclusion?.rawPublicResult ?? ''));

function sanitizeScoutRaw(raw) {
    if (!raw) return '';
    let text = String(raw).replace(/\[BEGIN_UNTRUSTED_TOOL_RESULT[\s\S]*?\[END_UNTRUSTED_TOOL_RESULT\]/g, '（工具输出已折叠）');
    const folded = [];
    let previous = null;
    let repeat = 0;
    for (const line of text.split(/\r?\n/)) {
        if (line === previous) { repeat += 1; continue; }
        if (previous !== null && repeat > 0) folded.push(`…（上一行重复 ${repeat + 1} 次，已折叠）`);
        folded.push(line);
        previous = line;
        repeat = 0;
    }
    if (previous !== null && repeat > 0) folded.push(`…（上一行重复 ${repeat + 1} 次，已折叠）`);
    text = folded.join('\n');
    const limit = 6000;
    return text.length > limit ? `${text.slice(0, limit)}\n\n…（内容过长，已截断）` : text;
}

function scopeRole(item) { return item?.role ?? item?.roleCode ?? item?.name ?? 'Agent'; }
function scopeText(item) {
    const value = item?.scope ?? item?.summary ?? item?.focus ?? item;
    return Array.isArray(value) ? value.join('、') : String(value ?? '');
}
</script>

<template>
    <section class="scout-conclusion-panel" aria-labelledby="scout-conclusion-title">
        <header>
            <div><p>结构化评审事实</p><h2 id="scout-conclusion-title">上下文收集结论</h2></div>
            <span v-if="conclusion">{{ evidencePaths.length }} 项查阅依据</span>
        </header>
        <div v-if="conclusion" class="scout-conclusion-body">
            <SafeMarkdown v-if="summary" class="scout-summary" :content="summary" />
            <div class="scout-conclusion-grid">
                <section><h3>关键模块</h3><ul v-if="moduleRoots.length"><li v-for="item in moduleRoots" :key="item">{{ item }}</li></ul><p v-else>未识别明确模块。</p></section>
                <section><h3>关键入口</h3><ul v-if="entryPoints.length"><li v-for="item in entryPoints" :key="item">{{ item }}</li></ul><p v-else>未识别明确入口。</p></section>
                <section><h3>约束</h3><ul v-if="constraints.length"><li v-for="item in constraints" :key="item">{{ item }}</li></ul><p v-else>没有公开约束。</p></section>
                <section><h3>风险</h3><ul v-if="risks.length"><li v-for="item in risks" :key="item">{{ item }}</li></ul><p v-else>没有公开风险。</p></section>
            </div>
            <section v-if="roleScopes.length" class="scout-role-scopes"><h3>角色关注范围</h3><dl><template v-for="(item, index) in roleScopes" :key="`${scopeRole(item)}-${index}`"><dt>{{ scopeRole(item) }}</dt><dd>{{ scopeText(item) }}</dd></template></dl></section>
            <details v-if="evidencePaths.length || cleanedRaw" class="scout-full-context">
                <summary>展开完整上下文</summary>
                <section v-if="evidencePaths.length"><h3>证据路径</h3><ul><li v-for="path in evidencePaths" :key="path"><code>{{ path }}</code></li></ul></section>
                <SafeMarkdown v-if="cleanedRaw" :content="cleanedRaw" />
            </details>
        </div>
        <div v-else class="scout-conclusion-empty"><strong>等待上下文侦察形成结论</strong><p>公开对话会先实时增长；收集完成后，这里将保留可重启回放的模块、入口、约束、风险与证据摘要。</p></div>
    </section>
</template>
