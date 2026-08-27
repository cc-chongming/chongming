<script setup>
import { formatChinaTime } from '../services/china-time';

defineProps({
    summary: { type: Object, default: null },
    plans: { type: Array, default: () => [] },
    roles: { type: Array, default: () => [] }
});

function payloadValue(event, name) {
    return event.payload?.[name] ?? '—';
}
</script>

<template>
    <section class="panel" aria-labelledby="plan-title">
        <div class="panel-heading">
            <div>
                <p class="eyebrow">执行概览</p>
                <h2 id="plan-title">计划与角色</h2>
            </div>
            <span v-if="summary?.progress !== null && summary?.progress !== undefined" class="progress-value">
                {{ summary.progress }}%
            </span>
        </div>

        <dl v-if="summary" class="summary-grid">
            <div><dt>阶段</dt><dd>{{ summary.stage || '等待事件' }}</dd></div>
            <div><dt>尝试</dt><dd>#{{ summary.attempt ?? '—' }}</dd></div>
            <div><dt>最后序列</dt><dd>{{ summary.lastSequence }}</dd></div>
            <div><dt>Gate</dt><dd>{{ summary.gate?.result ?? '未形成' }}</dd></div>
        </dl>

        <div class="progress-track" aria-label="评审进度">
            <span :style="{ width: `${summary?.progress ?? 0}%` }"></span>
        </div>

        <aside v-if="summary?.contextScout?.status === 'DEGRADED'" class="context-scout-warning" aria-label="上下文侦察降级信息">
            <strong>上下文侦察已降级</strong>
            <p>{{ summary.contextScout.publicSummary }}</p>
            <small>原因代码：{{ summary.contextScout.reasonCode }}<template v-if="summary.contextScout.occurredAt"> · {{ formatChinaTime(summary.contextScout.occurredAt) }}</template></small>
        </aside>

        <h3>活跃角色</h3>
        <ul v-if="roles.length" class="role-list" aria-label="角色状态">
            <li v-for="role in roles" :key="role.role">
                <strong>{{ role.role }}</strong>
                <span>{{ role.type }}</span>
            </li>
        </ul>
        <p v-else class="empty-note">尚未收到角色生命周期事件。</p>

        <h3>计划历史</h3>
        <ol v-if="plans.length" class="plan-history">
            <li v-for="plan in plans" :key="plan.sequence">
                <strong>{{ plan.type }}</strong>
                <span>v{{ plan.payload?.planVersion ?? '—' }} · {{ formatChinaTime(plan.occurredAt) }}</span>
                <p>{{ payloadValue(plan, 'changeReason') }}</p>
            </li>
        </ol>
        <p v-else class="empty-note">计划生成后会显示版本、修订原因和公开任务。</p>
    </section>
</template>
