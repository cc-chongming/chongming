<script setup>
import { ref } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { formatApiError, ReviewApiError } from '../api/review-api';
import { authStore } from '../stores/auth-store';

const router = useRouter();
const username = ref('');
const displayName = ref('');
const password = ref('');
const errorMessage = ref(null);
const submitting = ref(false);

async function handleSubmit() {
    if (submitting.value) return;
    errorMessage.value = null;
    submitting.value = true;
    try {
        // Registration signs the user in automatically per the backend contract.
        await authStore.register(username.value.trim(), password.value, displayName.value.trim());
        router.push('/dashboard');
    } catch (error) {
        errorMessage.value = error instanceof ReviewApiError ? error.message : formatApiError(error);
    } finally {
        submitting.value = false;
    }
}
</script>

<template>
    <div class="auth-page">
        <div class="auth-card create-form">
            <div class="auth-brand">
                <div class="logo-mark">重</div>
                <div>
                    <div class="auth-brand-name">重明</div>
                    <div class="auth-brand-sub">AI 需求评审平台</div>
                </div>
            </div>
            <h1 class="auth-title">注册</h1>
            <p class="auth-subtitle">创建账号后将自动登录</p>
            <form class="review-form" @submit.prevent="handleSubmit">
                <label>
                    用户名
                    <input v-model.trim="username" type="text" name="username" autocomplete="username" required>
                </label>
                <label>
                    显示名
                    <input v-model.trim="displayName" type="text" name="displayName" autocomplete="nickname" required>
                </label>
                <label>
                    密码
                    <input v-model="password" type="password" name="password" autocomplete="new-password" required>
                </label>
                <div v-if="errorMessage" class="error-banner" role="alert">{{ errorMessage }}</div>
                <div class="auth-actions">
                    <button class="button" type="submit" :disabled="submitting || !username || !displayName || !password">
                        {{ submitting ? '注册中…' : '注册' }}
                    </button>
                    <RouterLink class="auth-switch" to="/login">已有账号？返回登录</RouterLink>
                </div>
            </form>
        </div>
    </div>
</template>
