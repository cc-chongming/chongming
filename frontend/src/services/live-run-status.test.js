import { describe, expect, it } from 'vitest';
import { describeLiveRunState } from './live-run-status';

describe('live run status', () => {
    it('presents a failed review as a notice when no runtime trace exists', () => {
        expect(describeLiveRunState('FAILED', 'connected')).toEqual({
            connectionText: '运行已失败',
            emptyState: {
                title: '评审运行失败',
                message: '本次运行在产生可展示的 Agent 事件前失败。请返回工作台查看正式失败状态。'
            }
        });
    });

    it('keeps a non-terminal review in the live connection state', () => {
        expect(describeLiveRunState('PLANNING', 'connected')).toEqual({
            connectionText: '运行流已连接',
            emptyState: null
        });
    });
});
