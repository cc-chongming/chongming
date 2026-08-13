<script setup>
// [AIREVIEW-PLAN-023#7.2] Tools stay compact and collapsed; diagnostics are masked before display.
import { computed, ref } from 'vue';
import { maskSensitiveValue } from '../services/runtime-conversation-adapter';

const props = defineProps({ item: { type: Object, required: true } });
const copied = ref('');
const labels = {
    list_files: '列出文件', glob_files: '按模式查找文件', grep_files: '检索文件内容',
    read_file: '读取文件', search_text: '检索代码', open_debate_topic: '创建辩论议题'
};

const toolLabel = computed(() => labels[props.item.toolName] ?? props.item.toolName ?? '未知工具');
const effectiveStatus = computed(() => {
    // The terminal status is authoritative over the lifecycle phase: stale persisted
    // observations can carry phase=failed for tools that actually succeeded.
    const status = String(props.item.status ?? 'RUNNING').toUpperCase();
    if (status === 'SUCCESS') return 'SUCCESS';
    const phase = String(props.item.phase ?? '').toUpperCase();
    const outputState = String(props.item.output?.state ?? props.item.output?.status ?? props.item.output?.resultState ?? '').toUpperCase();
    if (['FAILED', 'ERROR'].includes(phase) || ['FAILED', 'ERROR', 'DENIED', 'INTERRUPTED'].includes(outputState)) return 'ERROR';
    return status;
});
const statusLabel = computed(() => ({
    RUNNING: '进行中', STREAMING: '进行中', SUCCESS: '已完成', COMPLETED: '已完成',
    ERROR: '失败', FAILED: '失败', DENIED: '已拒绝', INTERRUPTED: '已中断'
}[effectiveStatus.value] ?? effectiveStatus.value ?? '未知状态'));
const maskedInput = computed(() => maskSensitiveValue(props.item.input));
const maskedOutput = computed(() => maskSensitiveValue(props.item.output));
const outputSummary = computed(() => {
    const output = maskedOutput.value;
    if (!output) return '等待工具返回…';
    if (typeof output === 'string') return output.slice(0, 160);
    return output.summary ?? output.errorCode ?? output.text?.slice?.(0, 160) ?? '工具已返回结果，展开可查看详情。';
});
const elapsed = computed(() => props.item.elapsedMs == null ? null : `${props.item.elapsedMs}ms`);

function format(value) {
    if (value == null) return '暂无数据';
    if (typeof value === 'string') return value;
    try { return JSON.stringify(value, null, 2); } catch { return String(value); }
}

async function copy(kind, value) {
    try {
        await navigator.clipboard.writeText(format(value));
        copied.value = kind;
        window.setTimeout(() => { if (copied.value === kind) copied.value = ''; }, 1200);
    } catch {
        copied.value = '';
    }
}
</script>

<template>
    <article class="ag-ui-tool-call" :data-status="effectiveStatus">
        <details>
            <summary :aria-label="`${toolLabel}，${statusLabel}`">
                <span class="tool-call-symbol" aria-hidden="true">🔧</span>
                <strong>{{ toolLabel }}</strong>
                <code>{{ item.toolName }}</code>
                <span class="tool-call-status">{{ statusLabel }}</span>
                <span v-if="elapsed" class="tool-call-elapsed">{{ elapsed }}</span>
            </summary>
            <p class="tool-call-summary">{{ outputSummary }}</p>
            <p v-if="item.truncated" class="tool-call-truncated">内容已按安全上限截断。</p>
            <div class="tool-call-details">
                <section>
                    <header><h3>输入</h3><button type="button" @click="copy('input', maskedInput)">{{ copied === 'input' ? '已复制' : '复制' }}</button></header>
                    <pre>{{ format(maskedInput) }}</pre>
                </section>
                <section>
                    <header><h3>输出</h3><button type="button" @click="copy('output', maskedOutput)">{{ copied === 'output' ? '已复制' : '复制' }}</button></header>
                    <pre>{{ format(maskedOutput) }}</pre>
                </section>
            </div>
        </details>
    </article>
</template>
