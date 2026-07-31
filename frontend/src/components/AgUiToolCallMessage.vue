<script setup>
import { computed } from 'vue';

const props = defineProps({ item: { type: Object, required: true } });

const labels = {
    list_files: '列出文件',
    glob_files: '按模式查找文件',
    grep_files: '检索文件内容',
    read_file: '读取文件'
};

const toolLabel = computed(() => labels[props.item.toolName] ?? props.item.toolName);
const statusLabel = computed(() => ({
    RUNNING: '进行中', SUCCESS: '已完成', ERROR: '失败', DENIED: '已拒绝', INTERRUPTED: '已中断'
}[props.item.status] ?? props.item.status ?? '未知状态'));
const outputSummary = computed(() => {
    const output = props.item.output;
    if (!output) return '等待工具返回…';
    return output.summary ?? output.errorCode ?? output.text ?? '工具已完成，但没有可展示文本。';
});
const elapsed = computed(() => props.item.elapsedMs == null ? null : `${props.item.elapsedMs}ms`);

function format(value) {
    if (value == null) return 'null';
    try {
        return JSON.stringify(value, null, 2);
    } catch {
        return String(value);
    }
}
</script>

<template>
    <article class="ag-ui-tool-call" :data-status="item.status">
        <details>
            <summary :aria-label="`${toolLabel}，${statusLabel}`">
                <span class="tool-call-symbol" aria-hidden="true">›</span>
                <strong>{{ toolLabel }}</strong>
                <code>{{ item.toolName }}</code>
                <span class="tool-call-status">{{ statusLabel }}</span>
                <span v-if="elapsed" class="tool-call-elapsed">{{ elapsed }}</span>
            </summary>
            <p class="tool-call-summary">{{ outputSummary }}</p>
            <p v-if="item.truncated" class="tool-call-truncated">内容已按安全上限截断。</p>
            <div class="tool-call-details">
                <section>
                    <h3>输入</h3>
                    <pre>{{ format(item.input) }}</pre>
                </section>
                <section>
                    <h3>输出</h3>
                    <pre>{{ format(item.output) }}</pre>
                </section>
            </div>
        </details>
    </article>
</template>
