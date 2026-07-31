/**
 * [AIREVIEW-PLAN-020#4.2] Keeps terminal domain state visible when no AG-UI runtime event was emitted.
 */
export function describeLiveRunState(stage, connectionStatus) {
    const effectiveStage = stage ?? 'PENDING';
    const terminal = {
        FAILED: {
            connectionText: '运行已失败',
            emptyState: {
                title: '评审运行失败',
                message: '本次运行在产生可展示的 Agent 事件前失败。请返回工作台查看正式失败状态。'
            }
        },
        CANCELLED: {
            connectionText: '运行已取消',
            emptyState: {
                title: '评审已取消',
                message: '本次运行已取消，未产生可展示的 Agent 事件。'
            }
        },
        COMPLETED: {
            connectionText: '运行已完成',
            emptyState: {
                title: '评审运行已完成',
                message: '本次运行没有可展示的 Agent 事件。正式评审结果请返回工作台查看。'
            }
        }
    }[effectiveStage];
    if (terminal) return terminal;
    return {
        connectionText: connectionStatus === 'connected' ? '运行流已连接' : '正在连接运行流',
        emptyState: null
    };
}
