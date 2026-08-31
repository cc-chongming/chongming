package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewDispatchStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * [AIREVIEW-PLAN-024#方案4 收口] Server-side debate convergence fallback. The Director protocol
 * allows one stage action per wake, so a Director that keeps dispatching instead of closing
 * topics can loop for dozens of model round-trips. Once the Director wake budget or the debate
 * wall-clock budget is exhausted, the server itself escalates every non-terminal topic and
 * begins judging, deterministically, instead of letting the review grind forever.
 *
 * @author wangli
 */
@Service
public class DebateConvergenceGuard implements ReviewEventListener {

    private static final Logger log = LoggerFactory.getLogger(DebateConvergenceGuard.class);

    private final ReviewRegistry reviewRegistry;
    private final ObjectProvider<DebateService> debateServiceProvider;
    private final ReviewDebateStore debateStore;
    private final ReviewDispatchStore dispatchStore;
    private final AgentScopeProperties properties;
    private final ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider;
    private final ConcurrentMap<String, WakeState> states = new ConcurrentHashMap<>();

    // [AIREVIEW-PLAN-075#1] 旧构造兼容测试缝：直接注入 DebateService 实例。
    public DebateConvergenceGuard(
            ReviewRegistry reviewRegistry,
            DebateService debateService,
            ReviewDebateStore debateStore,
            AgentScopeProperties properties) {
        this(reviewRegistry, debateService, debateStore, null, properties);
    }

    // [AIREVIEW-PLAN-075#1] 旧 5 参构造：不注入运行时 trace 探针，no-progress 判定退化为旧语义。
    public DebateConvergenceGuard(
            ReviewRegistry reviewRegistry,
            DebateService debateService,
            ReviewDebateStore debateStore,
            ReviewDispatchStore dispatchStore,
            AgentScopeProperties properties) {
        this(reviewRegistry, debateService, debateStore, dispatchStore, properties, null);
    }

    // [AIREVIEW-PLAN-075#1] 旧 6 参构造（direct DebateService）：测试缝，保留既有调用签名。
    public DebateConvergenceGuard(
            ReviewRegistry reviewRegistry,
            DebateService debateService,
            ReviewDebateStore debateStore,
            ReviewDispatchStore dispatchStore,
            AgentScopeProperties properties,
            ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider) {
        this(reviewRegistry, fixedProvider(debateService), debateStore, dispatchStore, properties,
                traceRegistryProvider);
    }

    // [AIREVIEW-PLAN-075#1] Spring 首选构造：DebateService 经 ObjectProvider 懒解析，
    // 避免 ReviewEventService -> guard -> DebateService -> ReviewEventService 的 Bean 循环。
    @Autowired
    public DebateConvergenceGuard(
            ReviewRegistry reviewRegistry,
            ObjectProvider<DebateService> debateServiceProvider,
            ReviewDebateStore debateStore,
            ReviewDispatchStore dispatchStore,
            AgentScopeProperties properties,
            ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.debateServiceProvider = Objects.requireNonNull(debateServiceProvider,
                "debateServiceProvider must not be null");
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.dispatchStore = dispatchStore;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.traceRegistryProvider = traceRegistryProvider;
    }

    /**
     * [AIREVIEW-PLAN-075#1] Every committed domain event refreshes the per-attempt activity clock.
     * Only already-tracked attempts are updated, so this listener never keeps untracked reviews
     * resident. Terminal cleanup continues to be driven by {@link #clear(ReviewId, int)}.
     */
    @Override
    public void onCommitted(ReviewEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        WakeState state = states.get(key(event.reviewId(), event.attemptNo()));
        if (state == null) {
            return;
        }
        state.lastActivityAt = Instant.now();
    }

    /**
     * Counts one Director wake during the debate rounds; when either budget is exhausted the
     * guard force-converges exactly once and forgets the attempt.
     */
    public void noteDirectorWake(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        String key = key(reviewId, attemptNo);
        WakeState state = states.computeIfAbsent(key, ignored -> new WakeState());
        state.lastWakeAt = Instant.now();
        int wakes = state.wakes.incrementAndGet();
        Duration elapsed = Duration.between(state.firstWakeAt, Instant.now());
        // [AIREVIEW-PLAN-059#6] 串行辩论按议题数缩放预算：wakes 上限与墙钟 ×n，
        // 给 n 个议题串行走完足够预算；no-progress/expired-dispatch 两路快速救援不变。
        int topicScale = topicScale(reviewId);
        if (wakes < properties.debateMaxDirectorWakes() * topicScale
                && elapsed.compareTo(properties.debateConvergenceTimeout().multipliedBy(topicScale)) < 0) {
            return;
        }
        // [AIREVIEW-PLAN-075#1] 记录收敛瞬间的领域活动与运行时活动，供强制收敛日志诊断。
        Instant lastActivityAt = state.lastActivityAt;
        Instant lastTraceActivity = lastTraceActivity(reviewId, attemptNo);
        // Drop the state before converging so events published by the convergence itself never
        // re-enter with a stale counter.
        states.remove(key);
        forceConvergence(reviewId, attemptNo, wakes, elapsed, "wake-or-wall-clock", lastActivityAt,
                lastTraceActivity);
    }

    /** Forgetting the attempt state on judging/terminal events keeps counters attempt-scoped. */
    public void clear(ReviewId reviewId, int attemptNo) {
        states.remove(key(reviewId, attemptNo));
    }

    /**
     * Silent-stall watchdog. A tracked debate is force-converged as soon as any of three signals
     * fires, fastest first: (1) a pending dispatch command has expired (assigned work can never be
     * consumed → dead), (2) no new activity for {@code debateNoProgressTimeout} (no-progress idle),
     * or (3) the overall {@code debateConvergenceTimeout} wall-clock budget elapsed (backstop).
     */
    @Scheduled(fixedDelayString = "${review.agentscope.convergence-scan-interval:PT60S}")
    public void scanForStalledDebates() {
        Instant now = Instant.now();
        for (Map.Entry<String, WakeState> entry : states.entrySet()) {
            WakeState state = entry.getValue();
            Duration elapsed = Duration.between(state.firstWakeAt, now);
            // [AIREVIEW-PLAN-059#6] 墙钟预算按议题数缩放；no-progress 与 expired-dispatch 不缩放。
            String[] parts = entry.getKey().split(":");
            ReviewId parsedReviewId = null;
            int parsedAttemptNo = -1;
            int topicScale = 1;
            if (parts.length == 2) {
                try {
                    parsedReviewId = new ReviewId(UUID.fromString(parts[0]));
                    parsedAttemptNo = Integer.parseInt(parts[1]);
                    topicScale = topicScale(parsedReviewId);
                } catch (IllegalArgumentException ignored) {
                    //  malformed key: keep the unscaled budget; the parse below skips it anyway.
                }
            }
            String reason;
            if (hasExpiredPending(entry.getKey(), now)) {
                reason = "expired-dispatch";
            } else {
                // [AIREVIEW-PLAN-075#1] no-progress 判定纳入领域事件活动与 runtime trace 最后观测时间；
                // 三者取最新，任一通道有新活动都不算停摆。
                Instant last = lastActivity(state, parsedReviewId, parsedAttemptNo);
                Duration idle = Duration.between(last, now);
                if (idle.compareTo(properties.debateNoProgressTimeout()) >= 0) {
                    reason = "no-progress";
                } else if (elapsed.compareTo(properties.debateConvergenceTimeout().multipliedBy(topicScale)) >= 0) {
                    reason = "wall-clock";
                } else {
                    continue;
                }
            }
            // [AIREVIEW-PLAN-075#1] 记录收敛瞬间的领域活动与运行时活动，供强制收敛日志诊断。
            Instant lastActivityAt = state.lastActivityAt;
            Instant lastTraceActivity = parsedReviewId == null
                    ? Instant.EPOCH : lastTraceActivity(parsedReviewId, parsedAttemptNo);
            states.remove(entry.getKey());
            if (parts.length != 2) {
                continue;
            }
            try {
                forceConvergence(parsedReviewId, parsedAttemptNo,
                        state.wakes.get(), elapsed, reason, lastActivityAt, lastTraceActivity);
            } catch (IllegalArgumentException ignored) {
                // A malformed key can only come from programming error; never kill the scan.
            }
        }
    }

    /**
     * True when the attempt still holds a PENDING dispatch command past its expiry: the target role
     * never consumed it and never will, so the debate cannot make progress from it.
     */
    private boolean hasExpiredPending(String key, Instant now) {
        if (dispatchStore == null) {
            return false;
        }
        String[] parts = key.split(":");
        if (parts.length != 2) {
            return false;
        }
        try {
            ReviewId reviewId = new ReviewId(UUID.fromString(parts[0]));
            int attemptNo = Integer.parseInt(parts[1]);
            return dispatchStore.findPending(reviewId, attemptNo).stream()
                    .anyMatch(command -> command.isExpiredAt(now));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void forceConvergence(ReviewId reviewId, int attemptNo, int wakes, Duration elapsed, String reason,
            Instant lastActivity, Instant lastTraceActivity) {
        Review review = reviewRegistry.find(reviewId)
                .filter(candidate -> candidate.attemptNo() == attemptNo)
                .orElse(null);
        if (review == null) {
            return;
        }
        // [AIREVIEW-PLAN-075#1] 懒解析 DebateService：既打破 Bean 循环，也避免测试或停机窗口
        // 中缺少该服务时直接抛 NPE；此路径只是兜底收口，缺服务时明确跳过并告警。
        DebateService debateService = debateServiceProvider.getIfAvailable();
        if (debateService == null) {
            log.warn("DEBATE_FORCED_CONVERGENCE_SKIPPED reviewId={} attemptNo={} reason=DEBATE_SERVICE_MISSING",
                    reviewId.value(), attemptNo);
            return;
        }
        synchronized (review) {
            // [AIREVIEW-PLAN-047#1] The guard watches the single DEBATE phase plus the legacy
            // round stages so paused in-flight reviews stay covered.
            if (!isDebateStage(review.stage())) {
                return;
            }
            List<DebateTopic> openTopics = debateStore.findTopics(review.id()).stream()
                    .filter(topic -> !topic.status().isTerminal())
                    .toList();
            for (DebateTopic topic : openTopics) {
                debateService.closeTopic(review, new DebateToolCommands.CloseTopic(
                        new ReviewCommandMetadata(review.id(), review.version(),
                                new IdempotencyKey("debate-forced-convergence:" + topic.id().value())),
                        topic.id(),
                        DebateTopicStatus.ESCALATED,
                        "服务端强制收敛：辩论编排超出预算，本议题未达成公开决议，升级给 Judge 裁决。"));
            }
            debateService.beginJudging(review);
            // [AIREVIEW-PLAN-075#1] 扩展强制收敛日志：领域活动与运行时活动分别记录，便于判断
            // 触发原因是模型停摆还是仅缺少可观测事件。
            log.warn("DEBATE_FORCED_CONVERGENCE reviewId={} attemptNo={} directorWakes={} elapsed={} reason={} escalatedTopics={} lastActivity={} lastTraceActivity={}",
                    reviewId.value(), attemptNo, wakes, elapsed, reason, openTopics.size(),
                    lastActivity, lastTraceActivity);
        }
    }

    /**
     * [AIREVIEW-PLAN-075#1] no-progress 活性基准 = max(wake, committed event, runtime trace)；
     * 任一通道有新活动都会推迟 no-progress 收敛。key 畸形时退回 wake/domain 两个通道。
     */
    private Instant lastActivity(WakeState state, ReviewId reviewId, int attemptNo) {
        // [AIREVIEW-PLAN-075#1] EPOCH 哨兵值表示“该通道从未观测到活动”，必须排除在 max 之外，
        // 否则会把无领域事件/无 trace 的旧语义污染成永远不 idle 或错误 idle。
        Instant last = state.lastWakeAt;
        Instant domainActivity = state.lastActivityAt;
        if (!Instant.EPOCH.equals(domainActivity) && domainActivity.isAfter(last)) {
            last = domainActivity;
        }
        if (reviewId == null) {
            return last;
        }
        Instant traceActivity = lastTraceActivity(reviewId, attemptNo);
        if (!Instant.EPOCH.equals(traceActivity) && traceActivity.isAfter(last)) {
            last = traceActivity;
        }
        return last;
    }

    /** [AIREVIEW-PLAN-075#1] 未配置 probe 或 runtime 无 trace 时回退 epoch，保持旧 no-progress 语义。 */
    private Instant lastTraceActivity(ReviewId reviewId, int attemptNo) {
        ReviewRuntimeTraceRegistry traceRegistry = traceRegistryProvider == null
                ? null : traceRegistryProvider.getIfAvailable();
        if (traceRegistry == null) {
            return Instant.EPOCH;
        }
        return traceRegistry.lastObservedAt(ReviewRuntimeContext.runtimeIdFor(reviewId, attemptNo))
                .orElse(Instant.EPOCH);
    }

    /** [AIREVIEW-PLAN-059#6] 预算缩放系数 = max(1, 已登记议题数)；串行关闭或 store 异常时回退 1。 */
    private int topicScale(ReviewId reviewId) {
        if (!properties.debateSerialTopics()) {
            return 1;
        }
        try {
            return Math.max(1, debateStore.findTopics(reviewId).size());
        } catch (RuntimeException exception) {
            return 1;
        }
    }

    private static boolean isDebateStage(ReviewStage stage) {
        return stage == ReviewStage.DEBATE
                || stage == ReviewStage.DEBATE_ROUND_1
                || stage == ReviewStage.DEBATE_ROUND_2;
    }

    private static String key(ReviewId reviewId, int attemptNo) {
        return reviewId.value() + ":" + attemptNo;
    }

    /** [AIREVIEW-PLAN-075#1] 兼容旧构造签名的固定值 {@link ObjectProvider}，返回同一个实例。 */
    private static <T> ObjectProvider<T> fixedProvider(T value) {
        Objects.requireNonNull(value, "value must not be null");
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return List.of(value).iterator();
            }
        };
    }

    private static final class WakeState {
        private final Instant firstWakeAt = Instant.now();
        private final AtomicInteger wakes = new AtomicInteger();
        private volatile Instant lastWakeAt = Instant.now();
        // [AIREVIEW-PLAN-075#1] 领域事件活动，默认 epoch 使旧 no-progress 语义（只看 lastWakeAt）不变。
        private volatile Instant lastActivityAt = Instant.EPOCH;
    }
}
