import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useRepositoryOptions } from './use-repository-options';

describe('useRepositoryOptions [AIREVIEW-PLAN-023#2]', () => {
    it('automatically selects the only configured repository for a new form', async () => {
        const selectedRepository = ref('');
        const api = { listRepositories: vi.fn().mockResolvedValue([{ id: 'cx-ai', displayName: 'CX AI' }]) };
        const state = useRepositoryOptions(selectedRepository, { api });

        await state.loadRepositoryOptions();

        expect(state.options.value).toEqual([{ id: 'cx-ai', displayName: 'CX AI', type: 'local' }]);
        expect(selectedRepository.value).toBe('cx-ai');
        expect(state.loadError.value).toBe('');
    });

    it('labels remote repository options while keeping the opaque id contract [AIREVIEW-PLAN-028]', async () => {
        const selectedRepository = ref('');
        const api = {
            listRepositories: vi.fn().mockResolvedValue([
                { id: 'cx-ai', displayName: 'CX AI' },
                { id: 'demo-remote', displayName: '演示远程仓库', type: 'remote' }
            ])
        };
        const state = useRepositoryOptions(selectedRepository, { api });

        await state.loadRepositoryOptions();

        expect(state.options.value).toEqual([
            { id: 'cx-ai', displayName: 'CX AI', type: 'local' },
            { id: 'demo-remote', displayName: '演示远程仓库', type: 'remote' }
        ]);
    });

    it('keeps an unavailable historical repository read-only and requires an explicit replacement', async () => {
        const selectedRepository = ref('legacy-repository');
        const api = { listRepositories: vi.fn().mockResolvedValue([{ id: 'cx-ai', displayName: 'CX AI' }]) };
        const state = useRepositoryOptions(selectedRepository, { api });

        await state.loadRepositoryOptions();

        expect(state.unavailableRepositoryId.value).toBe('legacy-repository');
        expect(selectedRepository.value).toBe('');
        expect(state.options.value).toHaveLength(1);
    });

    it('does not provide a free-text fallback when repository options fail to load', async () => {
        const selectedRepository = ref('cx-ai');
        const api = { listRepositories: vi.fn().mockRejectedValue(new Error('offline')) };
        const state = useRepositoryOptions(selectedRepository, { api });

        await state.loadRepositoryOptions();

        expect(state.options.value).toEqual([]);
        expect(state.loadError.value).toBe('仓库配置读取失败，请重试。');
        expect(state.unavailableRepositoryId.value).toBe('cx-ai');
        expect(selectedRepository.value).toBe('');
        expect(state.canSelect.value).toBe(false);
    });
});
