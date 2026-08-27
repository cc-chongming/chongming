<script setup>
// [AIREVIEW-PLAN-049#1] 角色头像资产接入：按角色代码映射 role-avatars 资产（192×192 PNG），缺图时回退为单字文案。
import { computed } from 'vue';

const props = defineProps({
    role: { type: String, default: '' },
    fallback: { type: String, default: '' }
});

// [AIREVIEW-PLAN-049#1] 与两个组件内本地 roleInitial 映射保持一致的回退文案（缺省）。
const FALLBACK_MAP = {
    CONTEXT_SCOUT: '侦', DIRECTOR: '协', PRODUCT: '产', PROJECT: '项', FRONTEND: '前', BACKEND: '后',
    ARCHITECTURE: '架', SECURITY: '安', TESTING: '测', PERFORMANCE: '能', JUDGE: '裁'
};

// [AIREVIEW-PLAN-049#1] eager 收集 role-avatars 资产；文件名映射：CONTEXT_SCOUT→scout.png，其余 role.toLowerCase()+'.png'。
const roleAvatarAssets = import.meta.glob('../assets/role-avatars/*.png', { eager: true, import: 'default' });

function avatarAssetKey(role) {
    const code = String(role ?? '').toUpperCase();
    const fileName = code === 'CONTEXT_SCOUT' ? 'scout' : code.toLowerCase();
    return `../assets/role-avatars/${fileName}.png`;
}

const url = computed(() => roleAvatarAssets[avatarAssetKey(props.role)] ?? '');
const hasImage = computed(() => Boolean(url.value));
const displayText = computed(() => {
    if (props.fallback) return props.fallback;
    const code = String(props.role ?? '').toUpperCase();
    return FALLBACK_MAP[code] ?? (code ? code.slice(0, 1) : '审');
});
</script>

<template>
    <span class="role-avatar-slot" :class="{ 'has-image': hasImage }">
        <img v-if="hasImage" class="role-avatar-img" :src="url" :alt="displayText">
        <template v-else>{{ displayText }}</template>
    </span>
</template>
