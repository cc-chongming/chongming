import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * [AIREVIEW-PLAN-012#1.1] Builds the review workbench into Spring Boot static resources.
 */
export default defineConfig({
    plugins: [vue()],
    base: './',
    server: {
        proxy: {
            '/api': {
                target: process.env.VITE_API_TARGET ?? 'http://127.0.0.1:8080',
                changeOrigin: true
            }
        }
    },
    build: {
        outDir: '../src/main/resources/static/review',
        emptyOutDir: true,
        sourcemap: false
    },
    test: {
        environment: 'node',
        include: ['src/**/*.test.js']
    }
});
