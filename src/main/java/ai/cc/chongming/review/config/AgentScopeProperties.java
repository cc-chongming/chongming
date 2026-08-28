package ai.cc.chongming.review.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

/**
 * AgentScope runtime persistence settings.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.agentscope")
public record AgentScopeProperties(
        boolean persistSession,
        @NotBlank String stateHome,
        @DefaultValue("48") @Min(1) int directorMaxIterations,
        @DefaultValue("12") @Min(1) int scoutMaxIterations,
        @DefaultValue("20") @Min(1) int scoutMaxToolCalls,
        @DefaultValue("PT150S") Duration scoutTimeout,
        @DefaultValue("24") @Min(1) int debateMaxDirectorWakes,
        @DefaultValue("PT20M") Duration debateConvergenceTimeout,
        // [AIREVIEW-PLAN-024#收口] 无进展快速收敛窗口：Director 唤醒后若超过该时长仍无任何新活动
        // （或存在已过期的待消费调度命令），立即强制收敛，而不必等满 debateConvergenceTimeout 墙钟。
        // 需大于单次模型调用上限（director PT300S）以避免误收敛，默认 PT6M。
        @DefaultValue("PT6M") Duration debateNoProgressTimeout,
        // [AIREVIEW-PLAN-059#1] 议题串行辩论：任一时刻仅焦点议题（列表序第一个非终态议题）允许签发调度信封，
        // 其余议题排队、焦点终态后自动前进，避免并行议题淹没式签发导致全评审超时收敛。
        @DefaultValue("true") boolean debateSerialTopics,
        // [AIREVIEW-PLAN-060#4] 阶段活性心跳：超过该空窗时长仍未收到任何已提交事件时，
        // ReviewLivenessGuard 向该阶段滞留的角色重发一次唤醒指令；重唤醒次数超过
        // livenessMaxRewakes 后服务端确定性收口。
        @DefaultValue("PT90S") Duration livenessRewakeIdle,
        @DefaultValue("3") @Min(1) int livenessMaxRewakes) {

    @ConstructorBinding
    public AgentScopeProperties {
    }

    public AgentScopeProperties(boolean persistSession, String stateHome) {
        this(persistSession, stateHome, 48, 12, 16, Duration.ofSeconds(150), 24, Duration.ofMinutes(20),
                Duration.ofMinutes(6), true, Duration.ofSeconds(90), 3);
    }

    public AgentScopeProperties(boolean persistSession, String stateHome, int directorMaxIterations) {
        this(persistSession, stateHome, directorMaxIterations, 12, 16, Duration.ofSeconds(150), 24,
                Duration.ofMinutes(20), Duration.ofMinutes(6), true, Duration.ofSeconds(90), 3);
    }
}
