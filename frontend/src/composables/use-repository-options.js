import { computed, ref } from 'vue';
import { reviewApi } from '../api/review-api';

/**
 * Loads repository identifiers without exposing server filesystem paths.
 * Historical identifiers that are no longer configured are preserved only as a read-only warning.
 *
 * [AIREVIEW-PLAN-023#2]
 */
export function useRepositoryOptions(selectedRepository, { api = reviewApi } = {}) {
    const options = ref([]);
    const loading = ref(false);
    const loadError = ref('');
    const unavailableRepositoryId = ref('');
    const loaded = ref(false);
    const canSelect = computed(() => loaded.value && !loading.value && !loadError.value && options.value.length > 0);

    async function loadRepositoryOptions() {
        loading.value = true;
        loadError.value = '';
        try {
            const result = await api.listRepositories();
            options.value = Array.isArray(result)
                ? result.filter((item) => item && typeof item.id === 'string' && item.id.trim()).map((item) => ({
                    id: item.id.trim(),
                    displayName: typeof item.displayName === 'string' && item.displayName.trim()
                        ? item.displayName.trim()
                        : item.id.trim()
                }))
                : [];

            const current = String(selectedRepository.value ?? '').trim();
            if (current && !options.value.some((item) => item.id === current)) {
                unavailableRepositoryId.value = current;
                selectedRepository.value = '';
            } else {
                unavailableRepositoryId.value = '';
                if (!current && options.value.length === 1) {
                    selectedRepository.value = options.value[0].id;
                }
            }
        } catch {
            options.value = [];
            const current = String(selectedRepository.value ?? '').trim();
            if (current) {
                unavailableRepositoryId.value = current;
                selectedRepository.value = '';
            }
            loadError.value = '仓库配置读取失败，请重试。';
        } finally {
            loaded.value = true;
            loading.value = false;
        }
    }

    return {
        options,
        loading,
        loadError,
        unavailableRepositoryId,
        canSelect,
        loadRepositoryOptions
    };
}
