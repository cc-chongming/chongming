<script setup>
import { onMounted, ref, watch } from 'vue';
import { useRepositoryOptions } from '../composables/use-repository-options';

/** [AIREVIEW-PLAN-023#2] Shared repository selector backed exclusively by the safe repository options API. */
const props = defineProps({
    modelValue: { type: String, default: '' },
    label: { type: String, default: '目标仓库' },
    required: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false }
});
const emit = defineEmits(['update:modelValue']);
const selectedRepository = ref(props.modelValue ?? '');
const {
    options,
    loading,
    loadError,
    unavailableRepositoryId,
    canSelect,
    loadRepositoryOptions
} = useRepositoryOptions(selectedRepository);

watch(() => props.modelValue, (value) => {
    if ((value ?? '') !== selectedRepository.value) selectedRepository.value = value ?? '';
});
watch(selectedRepository, (value) => emit('update:modelValue', value));

onMounted(loadRepositoryOptions);
</script>

<template>
    <label class="repository-select">
        {{ label }}
        <select
            v-model="selectedRepository"
            :required="required"
            :disabled="disabled || !canSelect"
            :aria-busy="loading"
        >
            <option value="" disabled>
                {{ loading ? '正在读取仓库配置…' : loadError ? '仓库配置读取失败' : options.length ? '请选择仓库' : '暂无可用仓库' }}
            </option>
            <option v-for="repository in options" :key="repository.id" :value="repository.id">
                {{ repository.displayName }}（{{ repository.id }}）
            </option>
        </select>
        <span v-if="unavailableRepositoryId" class="muted repository-select-note">
            历史仓库“{{ unavailableRepositoryId }}”已不可用，请重新选择。
        </span>
        <span v-if="loadError" class="repository-select-error" role="alert">
            {{ loadError }}
            <button type="button" class="text-button" :disabled="loading" @click="loadRepositoryOptions">重新加载</button>
        </span>
    </label>
</template>
