import { describe, expect, it } from 'vitest';
import { isScoutConcluded, resolvePhaseLanding } from './review-phase-presenter';

describe('review phase landing', () => {
    it('lands on context scout while PLANNING and scout has not concluded', () => {
        // [AIREVIEW-PLAN-037#1] The stage machine reaches PLANNING before the scout stream ends;
        // the first entry must stop on the running scout, not the still-pending director planning.
        expect(resolvePhaseLanding({ stage: 'PLANNING', runtimeItems: [{ role: 'CONTEXT_SCOUT' }], scoutConcluded: false })).toBe(0);
    });

    it('moves to director planning once scout concluded by summary conclusion', () => {
        expect(resolvePhaseLanding({ stage: 'PLANNING', runtimeItems: [], scoutConcluded: true })).toBe(1);
    });

    it('keeps stage-based landing outside the PLANNING window', () => {
        expect(resolvePhaseLanding({ stage: 'SNAPSHOTTING', runtimeItems: [], scoutConcluded: false })).toBe(0);
        expect(resolvePhaseLanding({ stage: 'INITIAL_REVIEW', runtimeItems: [], scoutConcluded: false })).toBe(2);
        expect(resolvePhaseLanding({ stage: 'CONFLICT_DETECTION', runtimeItems: [], scoutConcluded: false })).toBe(3);
        // [AIREVIEW-PLAN-047#3] 单一 DEBATE 阶段与旧轮次值同样落位辩论。
        expect(resolvePhaseLanding({ stage: 'DEBATE', runtimeItems: [], scoutConcluded: false })).toBe(4);
        expect(resolvePhaseLanding({ stage: 'DEBATE_ROUND_1', runtimeItems: [], scoutConcluded: false })).toBe(4);
        expect(resolvePhaseLanding({ stage: 'DEBATE_ROUND_2', runtimeItems: [], scoutConcluded: false })).toBe(4);
        expect(resolvePhaseLanding({ stage: 'JUDGING', runtimeItems: [], scoutConcluded: false })).toBe(5);
        expect(resolvePhaseLanding({ stage: 'WAITING_HUMAN', runtimeItems: [], scoutConcluded: false })).toBe(6);
    });

    it('falls back to director planning for unknown stages', () => {
        expect(resolvePhaseLanding({ stage: 'SOMETHING_NEW', runtimeItems: [], scoutConcluded: true })).toBe(1);
    });

    it('treats PENDING as scout-landing only when scout runtime items exist', () => {
        expect(resolvePhaseLanding({ stage: 'PENDING', runtimeItems: [{ role: 'CONTEXT_SCOUT' }], scoutConcluded: false })).toBe(0);
        expect(resolvePhaseLanding({ stage: 'PENDING', runtimeItems: [], scoutConcluded: false })).toBe(1);
    });

    it('treats missing stage as PENDING', () => {
        expect(resolvePhaseLanding({ stage: null, runtimeItems: [], scoutConcluded: false })).toBe(1);
        expect(resolvePhaseLanding({ runtimeItems: [{ role: 'CONTEXT_SCOUT' }] })).toBe(0);
    });

    it('ignores malformed runtime items', () => {
        expect(resolvePhaseLanding({ stage: 'PENDING', runtimeItems: [null, undefined, {}], scoutConcluded: false })).toBe(1);
    });
});

describe('isScoutConcluded', () => {
    it('concludes when the summary carries a scout conclusion', () => {
        expect(isScoutConcluded({ contextScout: { status: 'COMPLETED' }, scoutRunFinished: false })).toBe(true);
        expect(isScoutConcluded({ contextScout: { status: 'DEGRADED' }, scoutRunFinished: false })).toBe(true);
    });

    it('concludes when the runtime RUN_FINISHED event arrived', () => {
        expect(isScoutConcluded({ contextScout: null, scoutRunFinished: true })).toBe(true);
    });

    it('stays open without either signal', () => {
        expect(isScoutConcluded({ contextScout: null, scoutRunFinished: false })).toBe(false);
        expect(isScoutConcluded()).toBe(false);
    });
});
