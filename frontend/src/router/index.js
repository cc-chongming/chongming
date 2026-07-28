import { createRouter, createWebHashHistory } from 'vue-router';
import ReviewCreateView from '../views/ReviewCreateView.vue';
import ReviewWorkbenchView from '../views/ReviewWorkbenchView.vue';
import ReviewLiveView from '../views/ReviewLiveView.vue';
import ReviewReportView from '../views/ReviewReportView.vue';

/**
 * [AIREVIEW-PLAN-012#1.1] Hash history keeps refreshes compatible with Spring static resource hosting.
 */
export default createRouter({
    history: createWebHashHistory(),
    routes: [
        { path: '/', redirect: '/create' },
        { path: '/create', name: 'review-create', component: ReviewCreateView },
        { path: '/reviews/:reviewId/live', name: 'review-live', component: ReviewLiveView, props: true },
        { path: '/reviews/:reviewId', name: 'review-workbench', component: ReviewWorkbenchView, props: true },
        { path: '/reviews/:reviewId/report', name: 'review-report', component: ReviewReportView, props: true },
        { path: '/:pathMatch(.*)*', redirect: '/create' }
    ]
});
