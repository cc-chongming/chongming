<script setup>
defineProps({ evidence: { type: Object, default: null } });
const emit = defineEmits(['close']);
</script>

<template>
    <aside v-if="evidence" class="evidence-drawer" aria-label="证据详情" aria-live="polite">
        <div class="drawer-heading">
            <div><p class="eyebrow">只读快照</p><h2>证据详情</h2></div>
            <button class="button secondary" type="button" @click="emit('close')">关闭</button>
        </div>
        <dl class="detail-list">
            <div><dt>快照路径</dt><dd>{{ evidence.snapshotRelativePath }}</dd></div>
            <div><dt>仓库修订</dt><dd>{{ evidence.repoRevision }}</dd></div>
            <div><dt>行号</dt><dd>{{ evidence.lineNumber }}</dd></div>
            <div><dt>片段 Hash</dt><dd>{{ evidence.excerptHash }}</dd></div>
            <div><dt>文件 Hash</dt><dd>{{ evidence.fileHash }}</dd></div>
        </dl>
        <pre class="code-excerpt"><code>{{ evidence.excerpt }}</code></pre>
        <p class="muted">页面仅按服务端证据 ID 查询，不会读取或拼接任意本地文件路径。</p>
    </aside>
</template>
