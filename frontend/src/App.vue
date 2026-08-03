<script setup>
import { RouterLink, RouterView } from 'vue-router';
</script>

<template>
    <a class="skip-link" href="#main-content">跳至主要内容</a>
    <div class="platform-shell">
        <aside class="platform-sidebar">
            <RouterLink class="brand" to="/dashboard">重明 · AI 需求评审</RouterLink>
            <nav aria-label="主导航" class="platform-nav">
                <RouterLink to="/dashboard">概览</RouterLink>
                <RouterLink to="/requirements">需求管理</RouterLink>
                <RouterLink to="/reviews">评审中心</RouterLink>
                <RouterLink to="/reports">评审报告</RouterLink>
            </nav>
            <div class="sidebar-footer"><RouterLink to="/requirements/create">+ 新建需求</RouterLink><RouterLink to="/create">旧版直接创建</RouterLink></div>
        </aside>
        <main id="main-content" class="app-main platform-main" tabindex="-1"><RouterView /></main>
    </div>
</template>
