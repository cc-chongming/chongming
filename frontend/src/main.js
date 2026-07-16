import { createApp } from 'vue';
import App from './App.vue';
import router from './router';
import './styles/review.css';

/** [AIREVIEW-PLAN-012#1.1] Vue workbench entry point. */
createApp(App).use(router).mount('#app');
