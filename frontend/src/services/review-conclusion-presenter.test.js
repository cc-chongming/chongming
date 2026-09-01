import { describe, expect, it } from 'vitest';
import {
    compareGateDecision,
    GATE_CONCLUSION_HINTS,
    humanizeGateReason,
    judgementReasonBlocks,
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

describe('humanizeGateReason [AIREVIEW-PLAN-023#6.3]', () => {
    it('把覆盖统计与已知触发原因合并翻译成中文', () => {
        expect(humanizeGateReason(
            'required=29, confirmed=9, partial=18, gap=3, unknown=3, notApplicable=0; P0/P1 claim lacks verified evidence'
        )).toBe('29 项必填检查点：9 项确认、18 项部分确认、3 项缺口、3 项未知；P0/P1 级主张缺少已验证证据');
    });

    it('为零项的覆盖统计不列出', () => {
        expect(humanizeGateReason(
            'required=10, confirmed=10, partial=0, gap=0, unknown=0, notApplicable=2; required checkpoints fully covered with no blocking item'
        )).toBe('10 项必填检查点：10 项确认、2 项不适用；必填检查点全部覆盖且无阻断项');
    });

    it('覆盖统计全部为零、无触发原因时给出简明措辞', () => {
        expect(humanizeGateReason('required=12, confirmed=0, partial=0, gap=0, unknown=0, notApplicable=0'))
            .toBe('12 项必填检查点均已覆盖');
    });

    it('只有英文触发原因时也翻译', () => {
        expect(humanizeGateReason('P0/P1 GAP lacks tracked disposition or evidence'))
            .toBe('P0/P1 级缺口缺少可追踪的处置或证据');
    });

    it('未命中的触发原因原样保留', () => {
        expect(humanizeGateReason('required=3, confirmed=1, gap=2; some brand new reason'))
            .toBe('3 项必填检查点：1 项确认、2 项缺口；some brand new reason');
    });

    it('带参数前缀的触发原因只翻译前缀并保留参数', () => {
        expect(humanizeGateReason('required=5, confirmed=1, gap=4; required checkpoint coverage incomplete: uncovered=DEV:launch-readiness'))
            .toBe('5 项必填检查点：1 项确认、4 项缺口；必填检查点覆盖不完整，未覆盖：DEV:launch-readiness');
    });

    it('无法解析的输入原样返回，空输入返回空串', () => {
        expect(humanizeGateReason('完全看不懂的内容')).toBe('完全看不懂的内容');
        expect(humanizeGateReason('')).toBe('');
        expect(humanizeGateReason(null)).toBe('');
        expect(humanizeGateReason(undefined)).toBe('');
    });

    it('结论含义说明覆盖五个提交值且文案齐全', () => {
        expect(GATE_CONCLUSION_HINTS.map((hint) => hint.result)).toEqual(['PASS', 'CONDITIONAL', 'BLOCK', 'RETURN', 'OVERRIDE']);
        for (const hint of GATE_CONCLUSION_HINTS) {
            expect(hint.label).toBeTruthy();
            expect(hint.description).toBeTruthy();
        }
    });
});

// [AIREVIEW-PLAN-102#1] 裁决理由按结构标签分段，标签加粗、每段独立成行。
describe('judgement reason blocks [AIREVIEW-PLAN-102#1]', () => {
    it('splits labelled segments and keeps the label with its colon', () => {
        const blocks = judgementReasonBlocks('核心争议：验收是否建立在未执行验证之上。项目方立场（反对，严重级）：集成测试持续跳过。采信依据：双方无分歧。裁决：保留验收判据。');
        expect(blocks.map((block) => block.label)).toEqual([
            '核心争议：', '项目方立场（反对，严重级）：', '采信依据：', '裁决：'
        ]);
        expect(blocks[0].text).toBe('验收是否建立在未执行验证之上。');
        expect(blocks.at(-1).text).toBe('保留验收判据。');
    });

    it('returns the raw text as one block when no label is present', () => {
        expect(judgementReasonBlocks('自由文本没有标签')).toEqual([{ label: null, text: '自由文本没有标签' }]);
        expect(judgementReasonBlocks('')).toEqual([]);
    });

    // [AIREVIEW-PLAN-103#1] 自冲突议题的反对原文必须进入决策依据：positions 质疑置前。
    it('projects topic claim positions with oppose first', () => {
        const result = presentDebateJudgement({
            topicId: 'topic-2',
            subjectKey: 'self-conflict',
            claims: [
                { role: 'PRODUCT', position: 'SUPPORT', severity: 'P2', statement: '支持陈述' },
                { role: 'PRODUCT', position: 'OPPOSE', severity: 'P1', statement: '反对点' }
            ],
            judgement: { result: 'CONDITIONAL', reasonSummary: '理由', acceptedClaimIds: [], rejectedClaimIds: [] }
        }, []);
        expect(result.positions.map((position) => position.position)).toEqual(['OPPOSE', 'SUPPORT']);
        expect(result.positions[0].statement).toBe('反对点');
        expect(result.positions[0].role).toBe('PRODUCT');
    });
});
