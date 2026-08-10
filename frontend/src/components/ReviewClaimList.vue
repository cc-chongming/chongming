<script setup>
// [AIREVIEW-PLAN-023#6.2] Claim statement is the primary readable content; subjectKey is metadata.
import { computed, ref } from 'vue';
import { gateLabel } from '../services/review-live-presenter';

const props = defineProps({ claims: { type: Array, default: () => [] }, initialLimit: { type: Number, default: 3 } });
const expanded = ref(false);
const visibleClaims = computed(() => expanded.value ? props.claims : props.claims.slice(0, props.initialLimit));
const severityLabel = { P0: '阻断', P1: '高风险', P2: '改进', P3: '提示' };

function evidenceIds(claim) {
    return claim.evidenceIds ?? claim.evidenceRefs ?? [];
}
</script>

<template>
    <div class="review-claim-list">
        <article v-for="claim in visibleClaims" :key="claim.claimId" class="readable-claim">
            <header><span :class="['flow-severity', claim.severity]">{{ claim.severity }} · {{ severityLabel[claim.severity] ?? '提示' }}</span><span>{{ gateLabel(claim.position) }}</span></header>
            <h3>{{ claim.statement || '该 Claim 暂无公开正文' }}</h3>
            <p v-if="claim.reasonSummary">{{ claim.reasonSummary }}</p>
            <small v-if="claim.subjectKey">技术标识：<code>{{ claim.subjectKey }}</code></small>
            <details v-if="evidenceIds(claim).length"><summary>{{ evidenceIds(claim).length }} 项证据</summary><ul><li v-for="evidence in evidenceIds(claim)" :key="typeof evidence === 'string' ? evidence : evidence.value"><code>{{ typeof evidence === 'string' ? evidence : evidence.value }}</code></li></ul></details>
        </article>
        <button v-if="claims.length > initialLimit" class="claim-expand-button" type="button" @click="expanded = !expanded">{{ expanded ? '收起其余 Claim' : `展开全部 ${claims.length} 项 Claim` }}</button>
    </div>
</template>
