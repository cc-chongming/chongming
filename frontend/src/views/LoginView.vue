<script setup>
import { ref } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { formatApiError, ReviewApiError } from '../api/review-api';
import { authStore } from '../stores/auth-store';

const route = useRoute();
const router = useRouter();
const username = ref('');
const password = ref('');
const errorMessage = ref(null);
const submitting = ref(false);

function safeRedirect() {
    const redirect = route.query.redirect;
    // Reject protocol-relative targets like "//evil.com" to close the open-redirect hole.
    return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
        ? redirect
        : '/dashboard';
}

async function handleSubmit() {
    if (submitting.value) return;
    errorMessage.value = null;
    submitting.value = true;
    try {
        await authStore.login(username.value.trim(), password.value);
        router.push(safeRedirect());
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
            <h1 class="auth-title">登录</h1>
            <p class="auth-subtitle">使用账号登录后继续需求评审工作</p>
            <form class="review-form" @submit.prevent="handleSubmit">
                <label>
                    用户名
                    <input v-model.trim="username" type="text" name="username" autocomplete="username" required>
                </label>
                <label>
                    密码
                    <input v-model="password" type="password" name="password" autocomplete="current-password" required>
                </label>
                <div v-if="errorMessage" class="error-banner" role="alert">{{ errorMessage }}</div>
                <div class="auth-actions">
                    <button class="button" type="submit" :disabled="submitting || !username || !password">
                        {{ submitting ? '登录中…' : '登录' }}
                    </button>
                    <RouterLink class="auth-switch" to="/register">还没有账号？去注册</RouterLink>
                </div>
            </form>
        </div>
    </div>
</template>
