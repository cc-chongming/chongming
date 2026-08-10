<script setup>
// [AIREVIEW-PLAN-023#7.1] Render only the supported, sanitized public Markdown subset.
import { computed } from 'vue';
import { renderSafeMarkdown } from '../services/safe-markdown';

const props = defineProps({ content: { type: String, default: '' } });
const html = computed(() => renderSafeMarkdown(props.content));
</script>

<template>
    <!-- renderSafeMarkdown escapes raw HTML and filters link protocols before v-html. -->
    <div class="safe-markdown" v-html="html"></div>
</template>
