import { createRouter, createWebHashHistory } from 'vue-router';
import { authStore } from '../stores/auth-store';
import ReviewCreateView from '../views/ReviewCreateView.vue';
import ReviewWorkbenchView from '../views/ReviewWorkbenchView.vue';
import ReviewLiveView from '../views/ReviewLiveView.vue';
import ReviewReportView from '../views/ReviewReportView.vue';
import ContextScoutPreviewView from '../views/ContextScoutPreviewView.vue';
import DashboardView from '../views/DashboardView.vue';
import RequirementListView from '../views/RequirementListView.vue';
import RequirementCreateView from '../views/RequirementCreateView.vue';
import RequirementDetailView from '../views/RequirementDetailView.vue';
import ReviewListView from '../views/ReviewListView.vue';
import ReportListView from '../views/ReportListView.vue';

/**
 * [AIREVIEW-PLAN-012#1.1] Hash history keeps refreshes compatible with Spring static resource hosting.
 */
const router = createRouter({
    history: createWebHashHistory(),
    routes: [
        { path: '/', redirect: '/dashboard' },
        { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
        { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { public: true } },
        { path: '/dashboard', name: 'dashboard', component: DashboardView },
        { path: '/requirements', name: 'requirements', component: RequirementListView },
        { path: '/requirements/create', name: 'requirement-create', component: RequirementCreateView },
        { path: '/requirements/:requirementId', name: 'requirement-detail', component: RequirementDetailView, props: true },
        { path: '/reviews', name: 'reviews', component: ReviewListView },
        { path: '/reports', name: 'reports', component: ReportListView },
        { path: '/create', name: 'review-create', component: ReviewCreateView },
        { path: '/reviews/:reviewId/live', name: 'review-live', component: ReviewLiveView, props: true },
        { path: '/reviews/:reviewId/scout', name: 'context-scout-preview', component: ContextScoutPreviewView, props: true },
        { path: '/reviews/:reviewId', name: 'review-workbench', component: ReviewWorkbenchView, props: true },
        { path: '/reviews/:reviewId/report', name: 'review-report', component: ReviewReportView, props: true },
        { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
    ]
});

/**
 * Auth guard: non-public routes require a locally valid token; signed-in users are kept
 * out of the auth pages. The original destination is preserved through the redirect query.
 */
router.beforeEach((to) => {
    // Re-check the persisted session so tokens expired while the tab was closed are dropped.
    authStore.restore();
    if (to.meta.public) {
        if ((to.name === 'login' || to.name === 'register') && authStore.isTokenValid()) {
            return { path: '/dashboard' };
        }
        return true;
    }
    if (!authStore.isTokenValid()) {
        return { path: '/login', query: { redirect: to.fullPath } };
    }
    return true;
});

export default router;
