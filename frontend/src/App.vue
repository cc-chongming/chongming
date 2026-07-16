<script setup>
import { RouterLink, RouterView } from 'vue-router';
</script>

<template>
    <a class="skip-link" href="#main-content">跳至主要内容</a>
    <header class="app-header">
        <RouterLink class="brand" to="/create">AI 需求评审</RouterLink>
        <nav aria-label="主导航">
            <RouterLink to="/create">创建评审</RouterLink>
        </nav>
    </header>
    <main id="main-content" class="app-main" tabindex="-1">
        <RouterView />
    </main>
</template>
