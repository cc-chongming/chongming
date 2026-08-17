<script setup>
import RepositorySelect from './RepositorySelect.vue';

/**
 * [AIREVIEW-PLAN-029] Repository binding picker: choose an administrator-configured repository
 * or supply an online repository URL with an optional branch and access token. The token is
 * write-only; edit flows pass `token-configured` so the placeholder can say "leave blank to
 * keep the previous token".
 */
const props = defineProps({
    modelValue: {
        type: Object,
        required: true
    },
    tokenConfigured: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    required: { type: Boolean, default: false }
});
const emit = defineEmits(['update:modelValue']);

function patch(changes) {
    emit('update:modelValue', { ...props.modelValue, ...changes });
}

function setMode(mode) {
    if (props.disabled || props.modelValue.mode === mode) return;
    patch({ mode });
}
</script>

<template>
    <div class="repo-source-picker">
        <div class="repo-source-tabs" role="tablist" aria-label="仓库来源">
            <button type="button" role="tab" class="repo-source-tab"
                :class="{ active: modelValue.mode !== 'remote' }"
                :aria-selected="modelValue.mode !== 'remote'"
                :disabled="disabled" @click="setMode('configured')">配置仓库</button>
            <button type="button" role="tab" class="repo-source-tab"
                :class="{ active: modelValue.mode === 'remote' }"
                :aria-selected="modelValue.mode === 'remote'"
                :disabled="disabled" @click="setMode('remote')">线上仓库</button>
        </div>
        <RepositorySelect
            v-if="modelValue.mode !== 'remote'"
            :model-value="modelValue.repositoryPath"
            :disabled="disabled"
            :required="required"
            @update:model-value="(value) => patch({ repositoryPath: value })" />
        <div v-else class="repo-source-remote">
            <label>仓库地址
                <input
                    name="remoteUrl"
                    :value="modelValue.remoteUrl"
                    placeholder="https://git.example.com/group/project.git"
                    maxlength="512"
                    :disabled="disabled"
                    :required="required"
                    @input="(event) => patch({ remoteUrl: event.target.value })" />
            </label>
            <label>分支（可选）
                <input
                    name="remoteRef"
                    :value="modelValue.remoteRef"
                    placeholder="默认使用仓库主分支"
                    maxlength="128"
                    :disabled="disabled"
                    @input="(event) => patch({ remoteRef: event.target.value })" />
            </label>
            <label>访问令牌（可选）
                <input
                    name="remoteToken"
                    type="password"
                    autocomplete="new-password"
                    :value="modelValue.remoteToken"
                    :placeholder="tokenConfigured ? '已配置令牌，留空保持不变' : '私有仓库需填写，仅用于本次评审克隆'"
                    maxlength="512"
                    :disabled="disabled"
                    @input="(event) => patch({ remoteToken: event.target.value })" />
            </label>
        </div>
    </div>
</template>
