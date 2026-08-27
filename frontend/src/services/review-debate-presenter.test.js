import { describe, expect, it } from 'vitest';
import { buildDefenseTurns } from './review-debate-presenter';

describe('review debate presenter (defense claim -> dialogue turns)', () => {
    const claimSubmit = (claimId, stage) => ({ type: 'CLAIM_SUBMITTED', claimId, stage });

    const debateTopic = (subjectKey, claims, extra = {}) => ({ subjectKey, claims, ...extra });

    const supportClaim = (claimId, role, statement, severity = 'P2', extra = {}) => ({
        claimId, role, position: 'SUPPORT', statement, severity, ...extra
    });

    it('初审提交（INITIAL_REVIEW）不合成答辩回合', () => {
        const debates = [debateTopic('S1', [supportClaim('c1', 'PRODUCT', '初审支持语')])];
        const events = [claimSubmit('c1', 'INITIAL_REVIEW')];
        expect(buildDefenseTurns(debates, events)).toEqual([]);
    });

    it('DEBATE_ROUND_1 提交合成为 round 1 的 REBUTTAL 回合', () => {
        const debates = [debateTopic('S1', [supportClaim('c1', 'PRODUCT', '需求成立，见约束文档')])];
        const events = [claimSubmit('c1', 'DEBATE_ROUND_1')];
        expect(buildDefenseTurns(debates, events)).toEqual([
            {
                turnId: 'c1',
                actorRole: 'PRODUCT',
                type: 'REBUTTAL',
                round: 1,
                subject: 'S1',
                content: '需求成立，见约束文档',
                severity: 'P2'
            }
        ]);
    });

    it('DEBATE_ROUND_2 提交合成为 round 2，多个议题按 round 升序稳定排序', () => {
        const debates = [
            debateTopic('S2', [supportClaim('c2', 'PRODUCT', '第二轮补充', 'P1')]),
            debateTopic('S1', [supportClaim('c1', 'PRODUCT', '第一轮答辩', 'P2')])
        ];
        const events = [
            claimSubmit('c2', 'DEBATE_ROUND_2'),
            claimSubmit('c1', 'DEBATE_ROUND_1')
        ];
        expect(buildDefenseTurns(debates, events)).toEqual([
            {
                turnId: 'c1', actorRole: 'PRODUCT', type: 'REBUTTAL', round: 1,
                subject: 'S1', content: '第一轮答辩', severity: 'P2'
            },
            {
                turnId: 'c2', actorRole: 'PRODUCT', type: 'REBUTTAL', round: 2,
                subject: 'S2', content: '第二轮补充', severity: 'P1'
            }
        ]);
    });

    it('同轮内保持议题/成员出现顺序（稳定）', () => {
        const debates = [
            debateTopic('S1', [supportClaim('c1', 'PRODUCT', '议题一答辩', 'P2')]),
            debateTopic('S2', [supportClaim('c2', 'BACKEND', '议题二答辩', 'P3')]),
            debateTopic('S3', [supportClaim('c3', 'SECURITY', '议题三答辩', 'P1')])
        ];
        const events = [
            claimSubmit('c3', 'DEBATE_ROUND_1'),
            claimSubmit('c1', 'DEBATE_ROUND_1'),
            claimSubmit('c2', 'DEBATE_ROUND_1')
        ];
        expect(buildDefenseTurns(debates, events).map((turn) => turn.turnId)).toEqual(['c1', 'c2', 'c3']);
    });

    it('只合成议题成员内的 Claim，孤立事件（无对应成员）不输出', () => {
        const debates = [debateTopic('S1', [supportClaim('c1', 'PRODUCT', '成员内答辩', 'P2')])];
        const events = [
            claimSubmit('c1', 'DEBATE_ROUND_1'),
            claimSubmit('orphan', 'DEBATE_ROUND_1')
        ];
        const turns = buildDefenseTurns(debates, events);
        expect(turns).toHaveLength(1);
        expect(turns[0].turnId).toBe('c1');
    });

    it('议题成员存在但无对应辩论轮事件时不合成', () => {
        const debates = [debateTopic('S1', [supportClaim('c1', 'PRODUCT', '无事件', 'P2')])];
        expect(buildDefenseTurns(debates, [])).toEqual([]);
        expect(buildDefenseTurns(debates, [{ type: 'CHALLENGE_SUBMITTED', claimId: 'c1', stage: 'DEBATE_ROUND_1' }])).toEqual([]);
    });

    it('同一 claimId 只输出一次（重复事件去重，取首次出现的轮次）', () => {
        const debates = [debateTopic('S1', [supportClaim('c1', 'PRODUCT', '只输出一次', 'P2')])];
        const events = [
            claimSubmit('c1', 'DEBATE_ROUND_1'),
            claimSubmit('c1', 'DEBATE_ROUND_2')
        ];
        const turns = buildDefenseTurns(debates, events);
        expect(turns).toHaveLength(1);
        expect(turns[0].round).toBe(1);
    });

    it('非 DEBATE_ROUND 前缀 / 无后缀轮次的 stage 跳过', () => {
        const debates = [debateTopic('S1', [supportClaim('c1', 'PRODUCT', '答辩', 'P2')])];
        const events = [
            claimSubmit('c1', 'DEBATE_PHASE'),
            claimSubmit('c1', 'DEBATE_ROUND_X'),
            claimSubmit('c1', 'DEBATE_ROUND_')
        ];
        expect(buildDefenseTurns(debates, events)).toEqual([]);
    });

    it('坏输入返回空数组', () => {
        expect(buildDefenseTurns(null, [])).toEqual([]);
        expect(buildDefenseTurns(undefined, [])).toEqual([]);
        expect(buildDefenseTurns([], null)).toEqual([]);
        expect(buildDefenseTurns([], undefined)).toEqual([]);
        expect(buildDefenseTurns('not-array', [])).toEqual([]);
        expect(buildDefenseTurns([], 'not-array')).toEqual([]);
    });

    it('对 null / 缺字段的议题、成员与事件保持健壮', () => {
        const debates = [
            null,
            {},
            { subjectKey: 'S1', claims: [null, {}, { claimId: 'c1' }, supportClaim('c2', 'PRODUCT', '答辩', 'P2')] },
            { subjectKey: 'S2', claims: 'not-array' }
        ];
        const events = [
            null,
            {},
            { type: 'CLAIM_SUBMITTED', stage: 'DEBATE_ROUND_1' },
            claimSubmit('c1', 'DEBATE_ROUND_1'),
            claimSubmit('c2', 'DEBATE_ROUND_2')
        ];
        const turns = buildDefenseTurns(debates, events);
        expect(turns).toHaveLength(2);
        expect(turns[0]).toEqual({
            turnId: 'c1', actorRole: undefined, type: 'REBUTTAL', round: 1,
            subject: 'S1', content: undefined, severity: undefined
        });
        expect(turns[1]).toEqual({
            turnId: 'c2', actorRole: 'PRODUCT', type: 'REBUTTAL', round: 2,
            subject: 'S1', content: '答辩', severity: 'P2'
        });
    });
});