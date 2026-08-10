import { describe, expect, it } from 'vitest';
import { claimOverview, completedReviewRoles, gateLabel, reviewRoles } from './review-live-presenter';

describe('review live presenter', () => {
    it('creates review roles from activation, claims and runtime identities', () => {
        const roles = reviewRoles(
            [{ role: 'PRODUCT' }, { role: 'SECURITY' }],
            [{ role: 'PERFORMANCE' }],
            [{ role: 'ARCHITECTURE' }]
        );

        expect(roles).toEqual(['PRODUCT', 'SECURITY', 'PERFORMANCE', 'ARCHITECTURE']);
    });

    it('merges completed summary roles with current-attempt ROLE_COMPLETED events without duplicates', () => {
        const roles = completedReviewRoles(
            [
                { role: 'PRODUCT', initialReviewCompleted: true },
                { role: 'SECURITY', initialReviewCompleted: false },
                { role: 'PRODUCT', initialReviewCompleted: true }
            ],
            [
                { type: 'ROLE_COMPLETED', actorRole: 'SECURITY', attemptNo: 2 },
                { type: 'ROLE_COMPLETED', actorRole: 'BACKEND', attemptNo: 2 },
                { type: 'ROLE_COMPLETED', actorRole: 'BACKEND', attemptNo: 2 },
                { type: 'ROLE_COMPLETED', actorRole: 'ARCHITECTURE', attemptNo: 1 },
                { type: 'ROLE_COMPLETED', actorRole: 'JUDGE', attemptNo: 2 }
            ],
            2
        );

        expect(roles).toEqual(['PRODUCT', 'SECURITY', 'BACKEND']);
        expect(completedReviewRoles([], [
            { type: 'ROLE_COMPLETED', actorRole: 'PRODUCT', attemptNo: 3 },
            { type: 'ROLE_COMPLETED', actorRole: 'PROJECT', attemptNo: 3 },
            { type: 'ROLE_COMPLETED', actorRole: 'FRONTEND', attemptNo: 3 },
            { type: 'ROLE_COMPLETED', actorRole: 'BACKEND', attemptNo: 3 }
        ], 3)).toEqual(['PRODUCT', 'PROJECT', 'FRONTEND', 'BACKEND']);
    });

    it('summarizes claim severity without exposing subject keys as body text', () => {
        expect(claimOverview([
            { severity: 'P0', subjectKey: 'internal.key', statement: '阻断问题' },
            { severity: 'P1', subjectKey: 'another.key', statement: '高风险问题' },
            { severity: 'P2', subjectKey: 'third.key', statement: '改进项' }
        ])).toBe('发现 3 项：1 项阻断、1 项高风险、1 项改进建议');
    });

    it('localizes gate enums', () => {
        expect(gateLabel('HUMAN_REQUIRED')).toBe('需要人工决策');
        expect(gateLabel('RETURN')).toBe('退回修改');
        expect(gateLabel('DRAFT')).toBe('草案');
    });
});
