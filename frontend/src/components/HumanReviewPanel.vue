<script setup>
import { reactive, ref } from 'vue';
import { formatApiError, reviewApi } from '../api/review-api';

const props = defineProps({
    reviewId: { type: String, required: true },
    items: { type: Array, default: () => [] },
    gateVersions: { type: Array, default: () => [] },
    reviewVersion: { type: Number, default: null }
});
const emit = defineEmits(['changed', 'error']);
const busy = ref(false);
const editingId = ref(null);
const message = ref('');
const draft = reactive(emptyDraft());
const decision = reactive({ result: 'PASS', reason: '', conditionsText: '', overrideReason: '' });

function emptyDraft() {
    return { type: 'RISK', severity: 'P2', title: '', content: '', action: '', claimIdsText: '', evidenceIdsText: '' };
}

function idList(value) {
    return value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean);
}

function payload(source) {
    return {
        type: source.type,
        severity: source.severity,
        title: source.title.trim(),
        content: source.content.trim(),
        action: source.action.trim(),
        claimIds: idList(source.claimIdsText ?? ''),
        evidenceIds: idList(source.evidenceIdsText ?? '')
    };
}

function assignDraft(source) {
    Object.assign(draft, source);
}

function reportError(error) {
    message.value = formatApiError(error);
    emit('error', error);
}

async function saveDraft() {
    busy.value = true;
    message.value = '';
    try {
        if (editingId.value) {
            const current = props.items.find((item) => item.itemId === editingId.value);
            await reviewApi.updateHumanItem(props.reviewId, editingId.value, current.version, payload(draft));
        } else {
            await reviewApi.createHumanItem(props.reviewId, payload(draft));
        }
        assignDraft(emptyDraft());
        editingId.value = null;
        message.value = '草稿已保存。';
        emit('changed');
    } catch (error) {
        reportError(error);
    } finally {
        busy.value = false;
    }
}

function edit(item) {
    editingId.value = item.itemId;
    assignDraft({
        type: item.type,
        severity: item.severity,
        title: item.title,
        content: item.content,
        action: item.action,
        claimIdsText: (item.claimIds ?? []).join(', '),
        evidenceIdsText: (item.evidenceIds ?? []).map((id) => typeof id === 'string' ? id : id.value).join(', ')
    });
}

function cancelEdit() {
    editingId.value = null;
    assignDraft(emptyDraft());
}

async function remove(item) {
    busy.value = true;
    message.value = '';
    try {
        await reviewApi.deleteHumanItem(props.reviewId, item.itemId, item.version);
        message.value = '草稿已删除。';
        if (editingId.value === item.itemId) cancelEdit();
        emit('changed');
    } catch (error) {
        reportError(error);
    } finally {
        busy.value = false;
    }
}

async function finalizeDecision() {
    if (props.reviewVersion === null) {
        message.value = '评审版本尚未加载，暂不能安全提交最终 Gate。';
        return;
    }
    busy.value = true;
    message.value = '';
    try {
        await reviewApi.createHumanGateDecision(props.reviewId, {
            expectedVersion: props.reviewVersion,
            result: decision.result,
            reason: decision.reason.trim(),
            conditions: idList(decision.conditionsText),
            overrideReason: decision.overrideReason.trim() || null
        });
        message.value = '最终 Gate 已提交；后续调整会创建新版本。';
        emit('changed');
    } catch (error) {
        reportError(error);
    } finally {
        busy.value = false;
    }
}
</script>

<template>
    <section class="panel human-panel" aria-labelledby="human-review-title">
        <div class="panel-heading"><div><p class="eyebrow">版本化人工介入</p><h2 id="human-review-title">人工审核</h2></div></div>
        <p class="muted">提交后版本只读；草稿修改和删除均使用服务端乐观锁版本。</p>
        <p v-if="message" class="inline-message" role="status">{{ message }}</p>

        <form class="review-form compact" @submit.prevent="saveDraft">
            <label>类型<select v-model="draft.type"><option value="RISK">风险</option><option value="REQUIREMENT">需求</option><option value="EVIDENCE">证据</option><option value="GATE">Gate</option></select></label>
            <label>严重度<select v-model="draft.severity"><option value="P0">P0</option><option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option></select></label>
            <label class="full">标题<input v-model="draft.title" maxlength="256" required /></label>
            <label class="full">公开描述<textarea v-model="draft.content" maxlength="8000" required></textarea></label>
            <label class="full">建议动作<input v-model="draft.action" maxlength="256" required /></label>
            <label>Claim ID（逗号分隔）<input v-model="draft.claimIdsText" /></label>
            <label>证据 ID（逗号分隔）<input v-model="draft.evidenceIdsText" /></label>
            <div class="form-actions full">
                <button class="button" :disabled="busy" type="submit">{{ editingId ? '保存修改' : '新增草稿' }}</button>
                <button v-if="editingId" class="button secondary" type="button" :disabled="busy" @click="cancelEdit">取消编辑</button>
            </div>
        </form>

        <ul v-if="items.length" class="human-items">
            <li v-for="item in items" :key="item.itemId">
                <div><span :class="['severity', item.severity]">{{ item.severity }}</span><strong>{{ item.title }}</strong><span class="muted">v{{ item.version }} · {{ item.status }}</span></div>
                <p>{{ item.content }}</p><p class="muted">动作：{{ item.action }}</p>
                <div class="item-actions"><button class="text-button" type="button" :disabled="busy" @click="edit(item)">编辑</button><button class="text-button danger" type="button" :disabled="busy" @click="remove(item)">删除</button></div>
            </li>
        </ul>
        <p v-else class="empty-note">暂无人工审核草稿。</p>

        <div class="gate-form">
            <h3>最终 Gate</h3>
            <p class="muted">当前评审版本：{{ reviewVersion ?? '加载中' }}。最终决定会触发报告与通知状态更新。</p>
            <form class="review-form compact" @submit.prevent="finalizeDecision">
                <label>结论<select v-model="decision.result"><option value="PASS">PASS</option><option value="CONDITIONAL">CONDITIONAL</option><option value="BLOCK">BLOCK</option><option value="RETURN">RETURN</option><option value="OVERRIDE">OVERRIDE</option></select></label>
                <label class="full">理由<textarea v-model="decision.reason" required></textarea></label>
                <label v-if="decision.result === 'CONDITIONAL'" class="full">条件（逗号或换行分隔）<textarea v-model="decision.conditionsText" required></textarea></label>
                <label v-if="decision.result === 'OVERRIDE'" class="full">Override 理由<textarea v-model="decision.overrideReason" required></textarea></label>
                <div class="form-actions full"><button class="button" type="submit" :disabled="busy || reviewVersion === null">提交最终 Gate</button></div>
            </form>
            <ol v-if="gateVersions.length" class="plan-history"><li v-for="gate in gateVersions" :key="gate.gateVersion"><strong>v{{ gate.gateVersion }} · {{ gate.result }}</strong><span>{{ gate.decidedAt }}</span><p>{{ gate.reason }}</p></li></ol>
        </div>
    </section>
</template>
