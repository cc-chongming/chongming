<script setup>
import { computed } from 'vue';

const props = defineProps({ events: { type: Array, required: true }, roles: { type: Array, required: true } });
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
            <aside><h3>角色席位</h3><button v-for="role in roles" :key="role.role" class="role-seat" type="button" @click="emit('inspect-role', role.role)"><strong>{{ role.role }}</strong><span>{{ role.type }} · 查看执行过程</span></button></aside>
            <div class="director-narrative"><h3>主持人叙事</h3><ol><li v-for="event in narrative" :key="event.sequence"><strong>{{ event.actorRole ?? 'DIRECTOR' }}</strong><span>{{ message(event) }}</span></li><li v-if="!narrative.length" class="empty-note">等待 Director 创建计划并分派角色。</li></ol></div>
        </div>
    </section>
</template>
