import { describe, expect, it } from 'vitest';
import {
    compareGateDecision,
    latestGateDecision,
    presentDebateJudgement,
    resolveAiGateDraft
} from './review-conclusion-presenter';

describe('review conclusion presenter [AIREVIEW-PLAN-023#6.3]', () => {
    const claims = [
        {
            claimId: 'claim-accepted',
            statement: '定时任务需要明确可验证范围',
            reasonSummary: '当前验收口径不能被自动化验证。'
        },
        {
            claimId: 'claim-rejected',
            statement: '只允许管理员触发调度',
            reasonSummary: '该限制没有产品依据。'
        }
    ];

    it('把 Judge 理由和采信、拒绝 Claim 投影为可阅读结果', () => {
        const result = presentDebateJudgement({
            topicId: 'topic-1',
            subjectKey: 'scheduledTask.core-scheduling.verifiable-scope',
            judgement: {
                result: 'CONDITIONAL',
                reasonSummary: '核心流程可行，但需先补齐可验证范围。',
                acceptedClaimIds: ['claim-accepted'],
                rejectedClaimIds: ['claim-rejected'],
                createdAt: '2026-08-10T10:00:00Z'
            }
        }, claims);

        expect(result.resultLabel).toBe('有条件通过');
        expect(result.reason).toBe('核心流程可行，但需先补齐可验证范围。');
        expect(result.accepted).toEqual([claims[0]]);
        expect(result.rejected).toEqual([claims[1]]);
    });

    it('保留当前报告中已不存在的 Claim ID，不伪装为空结果', () => {
        const result = presentDebateJudgement({
            subjectKey: 'legacy-topic',
            judgement: {
                result: 'PASS',
                reasonSummary: '',
                acceptedClaimIds: ['missing-claim'],
                rejectedClaimIds: []
            }
        }, claims);

        expect(result.reason).toBe('未提供公开裁决理由。');
        expect(result.accepted).toEqual([{ claimId: 'missing-claim', missing: true }]);
    });

    it('按 gateVersion 选择最新人工结果', () => {
        expect(latestGateDecision([
            { gateVersion: 2, result: 'BLOCK' },
            { gateVersion: 1, result: 'PASS' }
        ])).toEqual({ gateVersion: 2, result: 'BLOCK' });
        expect(latestGateDecision([])).toBeNull();
    });

    it('人工结果与 AI Gate 草案不同时输出明确差异', () => {
        expect(compareGateDecision(
            { result: 'CONDITIONAL', reasonSummary: '补齐验收条件后通过。' },
            { result: 'BLOCK', reason: '高风险问题未关闭。', overrideReason: '人工复核发现了新证据。' }
        )).toEqual({
            hasBoth: true,
            differs: true,
            draftLabel: '有条件通过',
            humanLabel: '驳回',
            draftReason: '补齐验收条件后通过。',
            humanReason: '高风险问题未关闭。',
            overrideReason: '人工复核发现了新证据。'
        });
    });

    it('缺少人工结果时不声称存在差异', () => {
        expect(compareGateDecision({ result: 'PASS', reasonSummary: '建议通过。' }, null)).toMatchObject({
            hasBoth: false,
            differs: false,
            draftLabel: '通过',
            humanLabel: '尚未提交'
        });
    });

    it('已有人工 Gate 时从原始 Gate 草案事件恢复 AI 结果，不误用摘要中的人工结果', () => {
        const draft = resolveAiGateDraft(
            { result: 'BLOCK', status: 'FINAL', reasonSummary: '人工最终理由' },
            [{ gateVersion: 1, result: 'BLOCK' }],
            [{
                sequence: 12,
                type: 'GATE_DRAFTED',
                occurredAt: '2026-08-10T09:00:00Z',
                payload: { result: 'CONDITIONAL', status: 'DRAFT' }
            }]
        );

        expect(draft).toEqual({
            result: 'CONDITIONAL',
            status: 'DRAFT',
            reasonSummary: '',
            decidedAt: '2026-08-10T09:00:00Z',
            source: 'event'
        });
    });

    it('人工 Gate 版本尚未刷新时也不把 HUMAN FINAL 摘要当成 AI 草案', () => {
        expect(resolveAiGateDraft(
            { result: 'BLOCK', status: 'FINAL', actor: 'HUMAN', reasonSummary: '人工最终理由' },
            [],
            [{
                sequence: 12,
                type: 'GATE_DRAFTED',
                occurredAt: '2026-08-10T09:00:00Z',
                payload: { result: 'PASS', status: 'DRAFT', reasonSummary: '安全门禁建议通过。' }
            }]
        )).toMatchObject({
            result: 'PASS',
            reasonSummary: '安全门禁建议通过。',
            source: 'event'
        });
    });

    it('人工 Gate 尚未提交时直接使用摘要中的 AI Gate 草案', () => {
        expect(resolveAiGateDraft(
            { result: 'PASS', status: 'DRAFT', reasonSummary: '风险可控。' },
            [],
            []
        )).toMatchObject({ result: 'PASS', reasonSummary: '风险可控。', source: 'summary' });
    });
});
