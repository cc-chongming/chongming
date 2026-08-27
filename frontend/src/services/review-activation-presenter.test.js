import { describe, expect, it } from 'vitest';
import { buildActivationRows } from './review-activation-presenter';

describe('review activation presenter', () => {
    const activated = (role, occurredAt) => ({ type: 'ROLE_ACTIVATED', actorRole: role, occurredAt });

    it('按激活事件出现顺序输出纯激活角色', () => {
        const rows = buildActivationRows([
            activated('FRONTEND', '2026-07-16 15:00:02'),
            { type: 'PLAN_CREATED', actorRole: 'DIRECTOR' },
            activated('BACKEND', '2026-07-16 15:00:05'),
            activated('ARCHITECTURE', '2026-07-16 15:00:07')
        ]);
        expect(rows).toEqual([
            { role: 'FRONTEND', state: 'activated', activatedAt: '2026-07-16 15:00:02' },
            { role: 'BACKEND', state: 'activated', activatedAt: '2026-07-16 15:00:05' },
            { role: 'ARCHITECTURE', state: 'activated', activatedAt: '2026-07-16 15:00:07' }
        ]);
    });

    it('ROLE_STARTED 将角色推进为初审中', () => {
        const rows = buildActivationRows([
            activated('SECURITY', '2026-07-16 15:00:10'),
            { type: 'ROLE_STARTED', actorRole: 'SECURITY', occurredAt: '2026-07-16 15:00:12' }
        ]);
        expect(rows).toEqual([
            { role: 'SECURITY', state: 'running', activatedAt: '2026-07-16 15:00:10' }
        ]);
    });

    it('ROLE_COMPLETED 将角色推进为初审完成（优先于 started）', () => {
        const rows = buildActivationRows([
            activated('TESTING', '2026-07-16 15:00:20'),
            activated('PERFORMANCE', '2026-07-16 15:00:22'),
            { type: 'ROLE_STARTED', actorRole: 'PERFORMANCE', occurredAt: '2026-07-16 15:00:25' },
            { type: 'ROLE_COMPLETED', actorRole: 'TESTING', occurredAt: '2026-07-16 15:00:30' }
        ]);
        expect(rows).toEqual([
            { role: 'TESTING', state: 'completed', activatedAt: '2026-07-16 15:00:20' },
            { role: 'PERFORMANCE', state: 'running', activatedAt: '2026-07-16 15:00:22' }
        ]);
    });

    it('忽略未知类型，仅归并角色生命周期事件', () => {
        const rows = buildActivationRows([
            { type: 'CLAIM_SUBMITTED', actorRole: 'FRONTEND' },
            activated('FRONTEND', '2026-07-16 15:00:01'),
            { type: 'RUN_FINISHED', actorRole: 'FRONTEND' }
        ]);
        expect(rows).toEqual([
            { role: 'FRONTEND', state: 'activated', activatedAt: '2026-07-16 15:00:01' }
        ]);
    });

    it('空数组 / 非法输入返回空数组', () => {
        expect(buildActivationRows([])).toEqual([]);
        expect(buildActivationRows(null)).toEqual([]);
        expect(buildActivationRows(undefined)).toEqual([]);
        expect(buildActivationRows([null, undefined, {}, { type: 'ROLE_ACTIVATED' }])).toEqual([]);
    });

    it('过滤缺失 actorRole 的事件', () => {
        expect(buildActivationRows([
            { type: 'ROLE_ACTIVATED', occurredAt: '2026-07-16 15:00:00' },
            { type: 'ROLE_STARTED', actorRole: null, occurredAt: '2026-07-16 15:00:01' },
            activated('JUDGE', '2026-07-16 15:00:02')
        ])).toEqual([
            { role: 'JUDGE', state: 'activated', activatedAt: '2026-07-16 15:00:02' }
        ]);
    });

    it('无激活事件但有完成/开始事件的角色兜底输出，且排在激活角色之后', () => {
        const rows = buildActivationRows([
            activated('FRONTEND', '2026-07-16 15:00:02'),
            { type: 'ROLE_COMPLETED', actorRole: 'BACKEND', occurredAt: '2026-07-16 15:00:04' },
            { type: 'ROLE_COMPLETED', actorRole: 'BACKEND', occurredAt: '2026-07-16 15:00:06' },
            { type: 'ROLE_STARTED', actorRole: 'ARCHITECTURE' }
        ]);
        expect(rows).toEqual([
            { role: 'FRONTEND', state: 'activated', activatedAt: '2026-07-16 15:00:02' },
            // 无激活事件：activatedAt 取最早相关事件 occurredAt
            { role: 'BACKEND', state: 'completed', activatedAt: '2026-07-16 15:00:04' },
            // 相关事件均无 occurredAt：回退为 null
            { role: 'ARCHITECTURE', state: 'running', activatedAt: null }
        ]);
    });
});
