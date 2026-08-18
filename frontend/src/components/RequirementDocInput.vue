<script setup>
// [AIREVIEW-PLAN-025] Requirement Markdown may arrive either as an uploaded .md file or as typed
// text; the intake endpoint accepts exactly one of the two transports.
import { computed, ref } from 'vue';

const props = defineProps({
    mode: { type: String, default: 'file' },
    text: { type: String, default: '' },
    file: { type: Object, default: null }
});
const emit = defineEmits(['update:mode', 'update:text', 'update:file']);

const fileInput = ref(null);
const dragging = ref(false);

const hasFile = computed(() => Boolean(props.file));

function setMode(mode) { emit('update:mode', mode); }
function onFileChange(event) { emit('update:file', event.target.files?.[0] ?? null); }
function openFilePicker() { fileInput.value?.click(); }
function fileSize() { return props.file ? `${(props.file.size / 1024).toFixed(1)} KB` : ''; }
function onDrop(event) {
    event.preventDefault();
    dragging.value = false;
    const dropped = event.dataTransfer?.files?.[0];
    if (dropped) emit('update:file', dropped);
}
</script>

<template>
    <div class="doc-input">
        <div class="doc-mode-switch" role="tablist" aria-label="需求文档提供方式">
            <button type="button" role="tab" :aria-selected="mode === 'file'" :class="{ active: mode === 'file' }" @click="setMode('file')">上传 Markdown 文档</button>
            <button type="button" role="tab" :aria-selected="mode === 'text'" :class="{ active: mode === 'text' }" @click="setMode('text')">手动输入内容</button>
        </div>
        <div v-if="mode === 'file'">
            <div class="upload-zone" :class="{ 'has-file': hasFile, dragging }" role="button" tabindex="0" aria-label="上传 Markdown 需求文档"
                 @click="openFilePicker" @keyup.enter="openFilePicker" @dragover.prevent="dragging = true" @dragleave="dragging = false" @drop="onDrop">
                <div class="uz-icon">{{ hasFile ? '✓' : '' }}</div>
                <div class="uz-txt">{{ hasFile ? file.name : '点击或拖拽上传 Markdown 需求文档' }}</div>
                <div class="uz-hint">{{ hasFile ? fileSize() : '支持 .md 格式，最大 2MB' }}</div>
            </div>
            <input ref="fileInput" type="file" accept=".md,text/markdown" class="uz-input" @change="onFileChange" />
        </div>
        <textarea v-else class="doc-textarea" :value="text" placeholder="直接粘贴或编写 Markdown 需求内容，例如：&#10;# 需求标题&#10;## 背景与目标&#10;## 验收标准"
                  aria-label="手动输入 Markdown 需求内容" @input="emit('update:text', $event.target.value)"></textarea>
    </div>
</template>

<style scoped>
.doc-mode-switch { display: flex; gap: 8px; margin-bottom: 8px; }
.doc-mode-switch button {
    padding: 6px 14px; border: 1px solid var(--line, #d8dee9); border-radius: 999px;
    background: transparent; color: var(--muted, #5b6472); cursor: pointer; font-size: 13px;
}
.doc-mode-switch button.active {
    background: var(--accent, #2563eb); border-color: var(--accent, #2563eb); color: #fff;
}
.doc-textarea {
    width: 100%; min-height: 180px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
    font-size: 13px; line-height: 1.6; padding: 10px 12px; border: 1px solid var(--line, #d8dee9);
    border-radius: 8px; background: var(--panel, #fff); color: inherit;
}
</style>
