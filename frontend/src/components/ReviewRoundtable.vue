<script setup>
import { computed } from 'vue';

// [AIREVIEW-PLAN-024#方案5] assessments is optional: seats show five-status conclusions when the
// workbench has the server projection, and degrade to the legacy layout without it.
const props = defineProps({
    events: { type: Array, required: true },
    roles: { type: Array, required: true },
    assessments: { type: Array, default: () => [] }
});
const emit = defineEmits(['inspect-role']);
const narrative = computed(() => props.events.filter((event) => [
    'PLAN_CREATED', 'ROLE_ACTIVATED', 'ROLE_COMPLETED', 'ROLE_FAILED', 'INITIAL_REVIEW_COMPLETED',
    'DEBATE_TOPIC_OPENED', 'CHALLENGE_SUBMITTED', 'REBUTTAL_SUBMITTED', 'DEBATE_TOPIC_CLOSED',
    'JUDGEMENT_SUBMITTED', 'GATE_DRAFTED', 'HUMAN_GATE_FINALIZED'
].includes(event.type)).slice(-12));
const phase = computed(() => {
    const last = props.events.at(-1);
    return last?.stage ?? 'PENDING';
});
const assessmentChipsByRole = computed(() => {
    const chips = new Map();
    (props.assessments ?? []).forEach((entry) => {
        if (!entry?.role) return;
        const counts = chips.get(entry.role) ?? { CONFIRMED: 0, PARTIAL: 0, GAP: 0, UNKNOWN: 0, NOT_APPLICABLE: 0 };
        if (Object.prototype.hasOwnProperty.call(counts, entry.status)) counts[entry.status] += 1;
        chips.set(entry.role, counts);
    });
    return chips;
});
function conclusionFor(role) {
    const counts = assessmentChipsByRole.value.get(role);
    if (!counts) return null;
    const parts = [];
    if (counts.CONFIRMED) parts.push(`确认 ${counts.CONFIRMED}`);
    if (counts.PARTIAL) parts.push(`部分 ${counts.PARTIAL}`);
    if (counts.GAP) parts.push(`缺口 ${counts.GAP}`);
    if (counts.UNKNOWN) parts.push(`未知 ${counts.UNKNOWN}`);
    if (counts.NOT_APPLICABLE) parts.push(`不适用 ${counts.NOT_APPLICABLE}`);
    return parts.length ? `结论：${parts.join(' · ')}` : null;
}
function assessmentsFor(role) {
    return (props.assessments ?? []).filter((entry) => entry?.role === role);
}
function message(event) {
    return event.payload?.publicSummary ?? event.payload?.statement ?? event.payload?.reasonSummary
        ?? `${event.actorRole ?? 'Director'} 执行了 ${event.type}`;
}
</script>

<template>
    <section class="panel roundtable-panel" aria-labelledby="roundtable-title">
        <div class="panel-heading"><div><p class="eyebrow">Director 主持流程</p><h2 id="roundtable-title">评审圆桌</h2></div><span class="topic-status">{{ phase }}</span></div>
        <ol class="stage-rail" aria-label="评审阶段"><li>初审立论</li><li>冲突识别</li><li>多 Agent 辩论</li><li>Judge 收束</li><li>人工 Gate</li></ol>
        <div class="roundtable-layout">
            <aside><h3>角色席位</h3><button v-for="role in roles" :key="role.role" class="role-seat" type="button" @click="emit('inspect-role', role.role)"><strong>{{ role.role }}</strong><span>{{ role.type }} · 查看执行过程</span><span v-if="conclusionFor(role.role)" class="seat-asmt">{{ conclusionFor(role.role) }}</span><span v-for="assessment in assessmentsFor(role.role)" :key="`${assessment.checkpointKey}:${assessment.status}`" class="seat-asmt-detail" :data-status="assessment.status">{{ assessment.summary }}</span></button></aside>
            <div class="director-narrative"><h3>主持人叙事</h3><ol><li v-for="event in narrative" :key="event.sequence"><strong>{{ event.actorRole ?? 'DIRECTOR' }}</strong><span>{{ message(event) }}</span></li><li v-if="!narrative.length" class="empty-note">等待 Director 创建计划并分派角色。</li></ol></div>
        </div>
    </section>
</template>
