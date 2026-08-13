<script setup>
import { computed, onMounted, ref } from 'vue';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { reviewApi } from './api/review-api';
import { authStore } from './stores/auth-store';

const route = useRoute();
const router = useRouter();
const isReviewFlow = computed(() => route.name === 'review-live');
// Auth pages render as standalone, centered screens without the platform chrome.
const isAuthFlow = computed(() => route.name === 'login' || route.name === 'register');
const counts = ref(null);
const sidebarCollapsed = ref(false);

const authUser = computed(() => authStore.currentUser.value);
const userDisplayName = computed(() => authUser.value?.displayName || authUser.value?.username || '未登录');
const userInitial = computed(() => (userDisplayName.value || '重').charAt(0));

function handleLogout() {
    authStore.logout();
    router.push('/login');
}

const pageTitles = {
    dashboard: '工作台',
    requirements: '需求库',
    'requirement-create': '新建需求',
    'requirement-detail': '需求详情',
    reviews: '评审列表',
    'review-workbench': '评审工作台',
    reports: '评审报告',
    'review-report': '评审报告',
    'review-create': '发起评审',
    tasks: '全部任务',
    'my-tasks': '我的任务',
    'task-detail': '任务详情'
};
const currentTitle = computed(() => pageTitles[route.name] ?? '工作台');

const totalRequirements = computed(() => {
    const byStatus = counts.value?.requirementStatusCounts;
    if (!byStatus) return null;
    return Object.values(byStatus).reduce((sum, n) => sum + (Number(n) || 0), 0);
});

const navGroups = computed(() => [
    { title: '概览', items: [{ to: '/dashboard', icon: '▦', label: '工作台' }] },
    {
        title: '需求管理',
        items: [
            { to: '/requirements', icon: '≡', label: '需求库', badge: totalRequirements.value }
        ]
    },
    { title: '评审', items: [{ to: '/reviews', icon: '⚖', label: '评审列表', badge: counts.value?.activeReviewCount }] },
    { title: '报告', items: [{ to: '/reports', icon: '↗', label: '评审报告' }] },
    {
        title: '任务中心',
        items: [
            { to: '/tasks', icon: '☰', label: '全部任务' },
            { to: '/tasks/mine', icon: '◎', label: '我的任务' }
        ]
    }
]);

/**
 * Exact navigation highlight: the requirements entry stays active across its detail/create
 * children, while sibling entries highlight only on their own page.
 */
function isItemActive(item) {
    const isReportDetail = /^\/reviews\/[^/]+\/report(\/|$)/.test(route.path);
    if (item.to === '/requirements') return route.path.startsWith('/requirements');
    if (item.to === '/tasks/mine') return route.path === '/tasks/mine';
    if (item.to === '/tasks') return route.path.startsWith('/tasks') && !route.path.startsWith('/tasks/mine');
    if (item.to === '/reports') return route.path.startsWith('/reports') || isReportDetail;
    if (item.to === '/reviews' && isReportDetail) return false;
    return route.path === item.to || route.path.startsWith(`${item.to}/`);
}

function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value;
}

onMounted(async () => {
    try {
        counts.value = await reviewApi.getDashboard();
    } catch {
        counts.value = null;
    }
});
</script>

<template>
    <a class="skip-link" href="#main-content">跳至主要内容</a>
    <div class="platform-shell" :class="{ 'review-flow-shell': isReviewFlow, 'auth-flow-shell': isAuthFlow, 'sidebar-collapsed': sidebarCollapsed && !isReviewFlow && !isAuthFlow }">
        <aside v-if="!isReviewFlow && !isAuthFlow" class="platform-sidebar" :class="{ collapsed: sidebarCollapsed }">
            <div class="logo-area">
                <div class="logo-mark">重</div>
                <div class="logo-text"><div class="logo">重明</div><div class="sub">需求生命周期管理</div></div>
            </div>
            <nav class="platform-nav" aria-label="主导航">
                <div v-for="group in navGroups" :key="group.title" class="nav-group">
                    <div class="nav-group-title">{{ group.title }}</div>
                    <RouterLink v-for="item in group.items" :key="item.to" :to="item.to" class="nav-item" :class="{ active: isItemActive(item) }">
                        <span class="nav-ico" aria-hidden="true">{{ item.icon }}</span>
                        <span class="nav-label">{{ item.label }}</span>
                        <span v-if="item.badge != null" class="badge-count">{{ item.badge }}</span>
                    </RouterLink>
                </div>
            </nav>
            <div class="user-area">
                <div class="avatar">{{ userInitial }}</div>
                <div><div class="user-name">{{ userDisplayName }}</div><div class="user-role">{{ authUser?.username ?? '' }}</div></div>
                <button type="button" class="text-button auth-logout" @click="handleLogout">退出登录</button>
            </div>
        </aside>
        <div class="platform-frame" :class="{ 'review-flow-frame': isReviewFlow }">
            <header v-if="!isReviewFlow && !isAuthFlow" class="main-topbar">
                <button type="button" class="sidebar-toggle" :title="sidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'" aria-label="折叠或展开侧边栏" @click="toggleSidebar"><span aria-hidden="true">☰</span></button>
                <div class="breadcrumb">
                    <RouterLink class="home" to="/dashboard">首页</RouterLink>
                    <span class="sep">/</span>
                    <span class="cur">{{ currentTitle }}</span>
                </div>
            </header>
            <main id="main-content" class="app-main platform-main" :class="{ 'review-flow-main': isReviewFlow }" tabindex="-1"><RouterView /></main>
        </div>
    </div>
</template>
