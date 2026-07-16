<script setup>
import { computed, ref } from 'vue';

const props = defineProps({ debates: { type: Array, default: () => [] } });
const emit = defineEmits(['open-evidence']);
const topic = ref('');
const role = ref('');
const severity = ref('');
const timeline = ref(null);

const topics = computed(() => props.debates.map((item) => ({ id: item.topicId, label: item.subjectKey })));
const roles = computed(() => [...new Set(props.debates.flatMap((item) => [
    ...(item.claims ?? []).map((claim) => claim.role),
    ...(item.turns ?? []).flatMap((turn) => [turn.actorRole, turn.targetRole])
]).filter(Boolean))].sort());

const filteredDebates = computed(() => props.debates.filter((item) => {
    if (topic.value && item.topicId !== topic.value) return false;
    const claims = (item.claims ?? []).filter((claim) => !severity.value || claim.severity === severity.value);
    const turns = (item.turns ?? []).filter((turn) => !role.value || [turn.actorRole, turn.targetRole].includes(role.value));
    const roleMatchesClaim = !role.value || (item.claims ?? []).some((claim) => claim.role === role.value);
    return (claims.length > 0 || turns.length > 0 || (!severity.value && !role.value))
        && (!role.value || roleMatchesClaim || turns.length > 0);
}));

function visibleClaims(item) {
    return (item.claims ?? []).filter((claim) => (!role.value || claim.role === role.value)
        && (!severity.value || claim.severity === severity.value));
}

function visibleTurns(item) {
    return (item.turns ?? []).filter((turn) => !role.value || [turn.actorRole, turn.targetRole].includes(role.value));
}

function moveToLatest() {
    timeline.value?.lastElementChild?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}
</script>

<template>
    <section class="panel timeline-panel" aria-labelledby="timeline-title">
        <div class="panel-heading">
            <div>
                <p class="eyebrow">公开推理结果</p>
                <h2 id="timeline-title">辩论时间线</h2>
            </div>
            <button class="button secondary" type="button" @click="moveToLatest">定位最新</button>
        </div>
        <p class="muted">仅展示公开论点、回合和裁决摘要，不展示角色隐藏思维链。</p>

        <div class="filters" aria-label="时间线筛选">
            <label>议题
                <select v-model="topic"><option value="">全部</option><option v-for="item in topics" :key="item.id" :value="item.id">{{ item.label }}</option></select>
            </label>
            <label>角色
                <select v-model="role"><option value="">全部</option><option v-for="item in roles" :key="item" :value="item">{{ item }}</option></select>
            </label>
            <label>严重度
                <select v-model="severity"><option value="">全部</option><option value="P0">P0</option><option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option></select>
            </label>
        </div>

        <div ref="timeline" class="timeline" aria-live="polite">
            <article v-for="item in filteredDebates" :key="item.topicId" class="topic-group">
                <header>
                    <span class="topic-status">{{ item.status }}</span>
                    <h3>{{ item.subjectKey }}</h3>
                    <p>第 {{ item.currentRound }} 回合 · {{ item.resolution || '尚未裁决' }}</p>
                </header>

                <article v-for="claim in visibleClaims(item)" :key="claim.claimId" class="timeline-card claim-card">
                    <div class="card-meta"><span :class="['severity', claim.severity]">{{ claim.severity }}</span><span>{{ claim.role }}</span><span>{{ claim.position }}</span></div>
                    <h4>Claim · {{ claim.subjectKey }}</h4>
                    <p>{{ claim.statement }}</p>
                    <p class="muted">{{ claim.reasonSummary }}</p>
                    <div v-if="claim.evidenceIds?.length" class="evidence-links">
                        <button v-for="evidenceId in claim.evidenceIds" :key="evidenceId" type="button" class="text-button" @click="emit('open-evidence', evidenceId)">查看证据</button>
                    </div>
                </article>

                <article v-for="turn in visibleTurns(item)" :key="turn.turnId" class="timeline-card turn-card">
                    <div class="card-meta"><span>{{ turn.type }}</span><span>{{ turn.actorRole }} → {{ turn.targetRole || '议题' }}</span><span>R{{ turn.round }}</span></div>
                    <p>{{ turn.content }}</p>
                    <p v-if="turn.stanceBefore || turn.stanceAfter" class="muted">立场：{{ turn.stanceBefore || '—' }} → {{ turn.stanceAfter || '—' }}</p>
                    <div v-if="turn.evidenceIds?.length" class="evidence-links">
                        <button v-for="evidenceId in turn.evidenceIds" :key="evidenceId" type="button" class="text-button" @click="emit('open-evidence', evidenceId)">查看证据</button>
                    </div>
                </article>

                <article v-if="item.judgement" class="timeline-card judgement-card">
                    <div class="card-meta"><span>JUDGEMENT</span><span>{{ item.judgement.result }}</span></div>
                    <p>{{ item.judgement.reasonSummary }}</p>
                    <p class="muted">接受 {{ item.judgement.acceptedClaimIds?.length ?? 0 }} 项，拒绝 {{ item.judgement.rejectedClaimIds?.length ?? 0 }} 项</p>
                </article>
            </article>
        </div>
        <p v-if="!filteredDebates.length" class="empty-note">当前筛选条件下没有公开的辩论卡片。</p>
    </section>
</template>
