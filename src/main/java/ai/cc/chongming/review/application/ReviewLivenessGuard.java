package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewDispatchStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * [AIREVIEW-PLAN-060#1,#2,#3] Stage liveness heartbeat. Review progress is event-driven and has no
 * polling cycle, so a lost wake or an empty model turn can leave INITIAL_REVIEW, CONFLICT_DETECTION
 * or JUDGING stalled forever. This guard tracks the last committed activity per attempt
 * (reviewId:attemptNo), re-wakes the stalled stage after {@code livenessRewakeIdle}, and
 * deterministically converges the stage once the server-side re-wake budget
 * ({@code livenessMaxRewakes}) is exhausted. DEBATE and PLANNING keep their own watchdogs.
 *
 * <p>[AIREVIEW-PLAN-063#1] Idle attempts additionally compensate-redeliver still-PENDING dispatch
 * envelopes (any stage, DEBATE included) up to the same per-commandId
 * {@code livenessMaxRewakes} budget, so a rejected-once envelope is never permanently silent;
 * redelivery does not change the envelope's PENDING status.
 *
 * @author wangli
 */
@Service
public class ReviewLivenessGuard implements ReviewEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewLivenessGuard.class);

    /** [AIREVIEW-PLAN-060#3] 服务端收口注册议题的幂等键前缀（元数据幂等键 liveness-register:<reviewId>）。 */
    private static final String LIVENESS_REGISTER_IDEMPOTENCY_PREFIX = "liveness-register:";

    /** [AIREVIEW-PLAN-060#2] 与 ReviewWorkflowDispatcher INITIAL_REVIEW_COMPLETED 分支同口径的 register/skip 指令。 */
    private static final String CONFLICT_DETECTION_REWAKE_MESSAGE =
            "All core initial reviews are complete. First call list_persisted_claims, then list_conflict_candidates. "
                    + "If at least one conflict candidate remains, register ALL chosen candidates in one "
                    + "register_topics batch command; only when no conflict candidate remains call "
                    + "skip_debate_when_no_conflicts. A single GAP or UNKNOWN assessment alone is never a "
                    + "debate topic. Do not search the workspace for Claim files or create facts in text.";

    /** [AIREVIEW-PLAN-060#2] 与 ReviewWorkflowDispatcher.dispatchJudge 相同文案。 */
    private static final String JUDGING_REWAKE_MESSAGE =
            "All debate topics are terminal. Use submit_judgement for each topic; if the topic list is empty, "
                    + "skip it. Then always call draft_gate exactly once so the judging stage can finish. "
                    + "Do not add facts.";

    private final ReviewRegistry reviewRegistry;
    private final AgentScopeProperties properties;
    private final ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider;
    private final ObjectProvider<ConflictDetectionService> conflictDetectionServiceProvider;
    private final ObjectProvider<DebateService> debateServiceProvider;
    private final ObjectProvider<JudgeService> judgeServiceProvider;
    private final ObjectProvider<ReviewCommandService> reviewCommandServiceProvider;
    private final ObjectProvider<ReviewDispatchStore> dispatchStoreProvider;
    private final ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider;
    private final ObjectProvider<ReviewDebateStore> debateStoreProvider;
    private final ConcurrentMap<String, LivenessState> states = new ConcurrentHashMap<>();

    /** [AIREVIEW-PLAN-063#1] 每个 commandId 的补偿重投计数，跨扫描保留，上限复用 livenessMaxRewakes。 */
    private final ConcurrentMap<String, AtomicInteger> redeliveries = new ConcurrentHashMap<>();

    /** [AIREVIEW-PLAN-069#5] redeliveries 无界保护：超过该容量时整体清空（终态清理同样会清）。 */
    private static final int MAX_REDELIVERY_TRACKED = 10_000;

    /** [AIREVIEW-PLAN-076#3] DEBATE 焦点议题静默关题阈值：距最后辩论回合事件与最后信封事件已超该时长。 */
    private static final Duration DEBATE_QUIESCENT_CLOSE = Duration.ofSeconds(90);

    /** [AIREVIEW-PLAN-063#1] 兼容旧构造：未注入 dispatch store 时补偿重投静默关闭（null 安全）。 */
    public ReviewLivenessGuard(
            ReviewRegistry reviewRegistry,
            AgentScopeProperties properties,
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ObjectProvider<ConflictDetectionService> conflictDetectionServiceProvider,
            ObjectProvider<DebateService> debateServiceProvider,
            ObjectProvider<JudgeService> judgeServiceProvider,
            ObjectProvider<ReviewCommandService> reviewCommandServiceProvider) {
        this(reviewRegistry, properties, runtimeAdapterProvider, conflictDetectionServiceProvider,
                debateServiceProvider, judgeServiceProvider, reviewCommandServiceProvider, null);
    }

    /**
     * [AIREVIEW-PLAN-072#2] 保留旧 8 参构造：仅注入 dispatch store，运行时活动探针传 null 关闭。
     */
    public ReviewLivenessGuard(
            ReviewRegistry reviewRegistry,
            AgentScopeProperties properties,
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ObjectProvider<ConflictDetectionService> conflictDetectionServiceProvider,
            ObjectProvider<DebateService> debateServiceProvider,
            ObjectProvider<JudgeService> judgeServiceProvider,
            ObjectProvider<ReviewCommandService> reviewCommandServiceProvider,
            ObjectProvider<ReviewDispatchStore> dispatchStoreProvider) {
        this(reviewRegistry, properties, runtimeAdapterProvider, conflictDetectionServiceProvider,
                debateServiceProvider, judgeServiceProvider, reviewCommandServiceProvider, dispatchStoreProvider, null);
    }

    /**
     * [AIREVIEW-PLAN-072#2] 保留旧 9 参构造：仅注入 dispatch store 与 trace probe；辩论议题 store 传 null
     * 关闭 DEBATE 静默关题，旧测试与旧部署路径语义不变。
     */
    public ReviewLivenessGuard(
            ReviewRegistry reviewRegistry,
            AgentScopeProperties properties,
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ObjectProvider<ConflictDetectionService> conflictDetectionServiceProvider,
            ObjectProvider<DebateService> debateServiceProvider,
            ObjectProvider<JudgeService> judgeServiceProvider,
            ObjectProvider<ReviewCommandService> reviewCommandServiceProvider,
            ObjectProvider<ReviewDispatchStore> dispatchStoreProvider,
            ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider) {
        this(reviewRegistry, properties, runtimeAdapterProvider, conflictDetectionServiceProvider,
                debateServiceProvider, judgeServiceProvider, reviewCommandServiceProvider, dispatchStoreProvider,
                traceRegistryProvider, null);
    }

    @Autowired
    public ReviewLivenessGuard(
            ReviewRegistry reviewRegistry,
            AgentScopeProperties properties,
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ObjectProvider<ConflictDetectionService> conflictDetectionServiceProvider,
            ObjectProvider<DebateService> debateServiceProvider,
            ObjectProvider<JudgeService> judgeServiceProvider,
            ObjectProvider<ReviewCommandService> reviewCommandServiceProvider,
            ObjectProvider<ReviewDispatchStore> dispatchStoreProvider,
            ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider,
            ObjectProvider<ReviewDebateStore> debateStoreProvider) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.runtimeAdapterProvider = Objects.requireNonNull(runtimeAdapterProvider, "runtimeAdapterProvider must not be null");
        this.conflictDetectionServiceProvider = conflictDetectionServiceProvider;
        this.debateServiceProvider = debateServiceProvider;
        this.judgeServiceProvider = judgeServiceProvider;
        this.reviewCommandServiceProvider = reviewCommandServiceProvider;
        // [AIREVIEW-PLAN-063#1] 可为 null（旧构造/测试缝）；redeliverPendingEnvelopes 内按 null 安全处理。
        this.dispatchStoreProvider = dispatchStoreProvider;
        // [AIREVIEW-PLAN-072#2] 可为 null（旧构造/测试缝）；扫描时按 null 安全退化为仅领域心跳。
        this.traceRegistryProvider = traceRegistryProvider;
        // [AIREVIEW-PLAN-076#3] 可为 null（旧构造/测试缝）；DEBATE 静默关题按 null 安全跳过。
        this.debateStoreProvider = debateStoreProvider;
    }

    /** [AIREVIEW-PLAN-060#1] committed events are the heartbeat feed; terminal events forget the attempt. */
    @Override
    public void onCommitted(ReviewEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String key = key(event.reviewId(), event.attemptNo());
        // [AIREVIEW-PLAN-069#5] 除 CANCELLED/FAILED 事件外，成功完成路径（stage COMPLETED 及其前置
        // NOTIFYING）同样视为终态：立即移除守卫状态并清空补偿重投计数，避免成功评审永久驻留。
        if (isTerminalEvent(event)) {
            if (states.remove(key) != null) {
                LOGGER.info("LIVENESS_STATE_CLEARED reviewId={} attemptNo={} eventType={} stage={}",
                        event.reviewId().value(), event.attemptNo(), event.type(), event.stage());
            }
            clearRedeliveries();
            return;
        }
        LivenessState state = states.computeIfAbsent(key, ignored -> new LivenessState());
        Instant now = Instant.now();
        state.lastActivityAt = now;
        // [AIREVIEW-PLAN-076#3] 辩论真回合/信封活动分别刷新静默关题观察窗。
        if (isDebateTurnEvent(event.type())) {
            state.lastDebateTurnAt = now;
        }
        if (isDispatchEnvelopeEvent(event.type())) {
            state.lastEnvelopeAt = now;
        }
        ReviewStage stage = event.stage();
        // [AIREVIEW-PLAN-060#3] 阶段变化即视为取得进展，重唤醒计数按 attempt+stage 归零。
        if (state.stage != stage) {
            state.stage = stage;
            state.rewakes.clear();
        }
    }

    /**
     * [AIREVIEW-PLAN-060#2] Periodically scans tracked attempts. A tracked attempt is idle when no
     * committed event arrived for {@code livenessRewakeIdle}; only the three covered stages are
     * re-woken, and only when the registry still owns the same attempt in the same stage.
     */
    @Scheduled(fixedDelayString = "${review.agentscope.liveness-scan-interval:PT60S}")
    public void scan() {
        // [AIREVIEW-PLAN-069#5] 容量上限兜底：即使终态清理被跳过，redeliveries 也不允许无界增长。
        if (redeliveries.size() > MAX_REDELIVERY_TRACKED) {
            LOGGER.warn("LIVENESS_REDELIVERY_CAP_RESET tracked={}", redeliveries.size());
            redeliveries.clear();
        }
        Instant now = Instant.now();
        for (Map.Entry<String, LivenessState> entry : states.entrySet()) {
            String key = entry.getKey();
            LivenessState state = entry.getValue();
            try {
                String[] parts = key.split(":");
                if (parts.length != 2) {
                    continue;
                }
                ReviewId reviewId;
                int attemptNo;
                try {
                    reviewId = new ReviewId(UUID.fromString(parts[0]));
                    attemptNo = Integer.parseInt(parts[1]);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                String runtimeId = ReviewRuntimeContext.runtimeIdFor(reviewId, attemptNo);
                // [AIREVIEW-PLAN-072#2] 活性判定纳入运行时最后观测时间：任一通道有新活动都不算停摆。
                Instant lastActivity = lastActivity(state, runtimeId);
                if (Duration.between(lastActivity, now)
                        .compareTo(properties.livenessRewakeIdle()) < 0) {
                    continue;
                }
                ReviewStage stage = state.stage;
                Review review = reviewRegistry.find(reviewId)
                        .filter(candidate -> candidate.attemptNo() == attemptNo
                                && candidate.stage() == stage)
                        .orElse(null);
                if (review == null) {
                    continue;
                }
                AgentRuntimeAdapter adapter = runtimeAdapterProvider.getIfAvailable();
                if (adapter == null) {
                    continue;
                }
                // [AIREVIEW-PLAN-063#1] PENDING 信封补偿重投对全部阶段生效（DEBATE 也在内），
                // 位于阶段覆盖判断之前；每个 commandId 独立计数，上限复用 livenessMaxRewakes。
                redeliverPendingEnvelopes(review, attemptNo, adapter);
                // [AIREVIEW-PLAN-076#3] DEBATE 静默关题：重投之后、阶段覆盖判断之前，仅处理焦点议题。
                if (stage == ReviewStage.DEBATE) {
                    closeQuiescentDebate(review, attemptNo, now);
                }
                if (!isCoveredStage(stage)) {
                    continue;
                }
                boolean rewoken = rewake(review, stage, adapter);
                if (!rewoken) {
                    continue;
                }
                // [AIREVIEW-PLAN-060#3] 每次扫描对同一 attempt+stage 只递增一次计数。
                int rewakes = state.rewakes.computeIfAbsent(stage, ignored -> new AtomicInteger())
                        .incrementAndGet();
                LOGGER.info("LIVENESS_REWAKE reviewId={} attemptNo={} stage={} rewakes={} max={}",
                        review.id().value(), attemptNo, stage, rewakes, properties.livenessMaxRewakes());
                if (rewakes > properties.livenessMaxRewakes()) {
                    // Drop the state before converging so events published by the closure itself
                    // never re-enter with a stale counter (same pattern as DebateConvergenceGuard).
                    states.remove(key);
                    converge(review, stage);
                }
            } catch (RuntimeException exception) {
                // A single malformed or raced attempt must never kill the whole scan.
                LOGGER.warn("LIVENESS_SCAN_FAILED key={} error={}", key, exception.toString());
            }
        }
    }

    /**
     * [AIREVIEW-PLAN-072#2] 领域心跳与运行时最后观测时间取较大值；未配置 probe 或该 runtime 无
     * trace 时回退到领域心跳，保持 PLAN-060 停摆判定的旧语义。
     */
    private Instant lastActivity(LivenessState state, String runtimeId) {
        Instant lastActivity = state.lastActivityAt;
        ReviewRuntimeTraceRegistry traceRegistry = providerOrNull(traceRegistryProvider);
        if (traceRegistry != null) {
            Optional<Instant> lastObservedAt = traceRegistry.lastObservedAt(runtimeId);
            if (lastObservedAt.isPresent() && lastObservedAt.get().isAfter(lastActivity)) {
                lastActivity = lastObservedAt.get();
            }
        }
        return lastActivity;
    }

    private boolean rewake(Review review, ReviewStage stage, AgentRuntimeAdapter adapter) {
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        return switch (stage) {
            case INITIAL_REVIEW -> rewakeIncompleteRoles(review, runtimeId, adapter);
            case CONFLICT_DETECTION -> {
                send(adapter, runtimeId, directorLabel(runtimeId), CONFLICT_DETECTION_REWAKE_MESSAGE);
                yield true;
            }
            case JUDGING -> {
                send(adapter, runtimeId, roleLabel(runtimeId, RoleType.JUDGE), JUDGING_REWAKE_MESSAGE);
                yield true;
            }
            default -> false;
        };
    }

    /** [AIREVIEW-PLAN-060#2] 初审停摆：给每个尚未 completeInitialReview 的已激活角色单独发提醒。 */
    private boolean rewakeIncompleteRoles(Review review, String runtimeId, AgentRuntimeAdapter adapter) {
        boolean sent = false;
        for (RoleActivation activation : review.roleActivations()) {
            if (activation.initialReviewCompleted()) {
                continue;
            }
            RoleType rt = activation.roleType();
            // [AIREVIEW-PLAN-071#1] 裁决者/协调者不参与初审收尾，排除误唤醒（与 requireActiveInitialReviewer 同口径）
            if (rt == RoleType.DIRECTOR || rt == RoleType.JUDGE) continue;
            send(adapter, runtimeId, roleLabel(runtimeId, activation.roleType()),
                    "初审仍未完成，请尽快提交 Claim 并调用 complete_initial_review。"
                            + "若你的初审已完成（initialReviewCompleted），不要重提交任何评估或主张，不要重贴结论，仅用一行确认状态。");
            sent = true;
        }
        return sent;
    }

    /**
     * [AIREVIEW-PLAN-063#1] 补偿重投：把该 attempt 仍 PENDING 且未过期的调度信封重新注入对应角色
     * （roleLabel/runtimeId 口径与 ReviewWorkflowDispatcher 一致，DEBATE 等未覆盖阶段同样生效）。
     * 每次扫描每个 commandId 只计一次；超过 {@code livenessMaxRewakes} 后交还既有看门狗收敛路径。
     * 重投不改变信封状态（仍 PENDING），消费/过期/拒绝语义不变。
     */
    private void redeliverPendingEnvelopes(Review review, int attemptNo, AgentRuntimeAdapter adapter) {
        if (dispatchStoreProvider == null) {
            return;
        }
        ReviewDispatchStore store = dispatchStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), attemptNo);
        // [AIREVIEW-PLAN-075#2] 已注入的 runtime trace probe：接收方运行时在命令签发后仍有活动时，
        // 说明信封已到达或被绕过且对方在推进，重投只会制造重复噪声，跳过该 PENDING 信封。
        ReviewRuntimeTraceRegistry traceRegistry = providerOrNull(traceRegistryProvider);
        for (ReviewDispatchCommand command : store.findPending(review.id(), attemptNo)) {
            if (command.isExpiredAt(Instant.now())) {
                continue;
            }
            if (traceRegistry != null) {
                Optional<Instant> lastObservedAt = traceRegistry.lastObservedAt(runtimeId);
                if (lastObservedAt.isPresent() && lastObservedAt.get().isAfter(command.createdAt())) {
                    String recipient = runtimeId + "-" + command.recipientRole().name().toLowerCase(Locale.ROOT);
                    LOGGER.info("REDELIVERY_SKIPPED_RECIPIENT_ACTIVE reviewId={} attemptNo={} commandId={} recipient={} action={} lastTraceActivity={} createdAt={}",
                            review.id().value(), attemptNo, command.commandId().value(), recipient,
                            command.allowedAction(), lastObservedAt.get(), command.createdAt());
                    continue;
                }
            }
            int count = redeliveries
                    .computeIfAbsent(command.commandId().value().toString(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (count > properties.livenessMaxRewakes()) {
                continue;
            }
            String recipient = runtimeId + "-" + command.recipientRole().name().toLowerCase(Locale.ROOT);
            adapter.deliverDispatchCommand(runtimeId, recipient,
                            ReviewDispatchService.envelopeText(command), command)
                    .subscribe();
            LOGGER.info("LIVENESS_ENVELOPE_REDELIVERED reviewId={} attemptNo={} commandId={} recipient={} action={} count={}",
                    review.id().value(), attemptNo, command.commandId().value(), recipient,
                    command.allowedAction(), count);
        }
    }

    /**
     * [AIREVIEW-PLAN-076#3] DEBATE 静默关题：焦点议题在最后辩论回合/信封活动后静默超过阈值、无 PENDING
     * 信封、且具备可收口事实（已有回合，或无 SUPPORT，或质询角色队列已空）时，确定性关闭焦点议题；
     * 若关题后所有议题已终态则进入裁决。同步在 review 上执行并吞异常，确保单次扫描永不中断。
     */
    private void closeQuiescentDebate(Review review, int attemptNo, Instant now) {
        ReviewDebateStore debateStore = providerOrNull(debateStoreProvider);
        if (debateStore == null) {
            return;
        }
        // [AIREVIEW-PLAN-076#3] 无法遍历 PENDING 信封时不做静默关题，避免把仍在途的命令误判为已完成。
        if (providerOrNull(dispatchStoreProvider) == null) {
            return;
        }
        LivenessState state = states.get(key(review.id(), attemptNo));
        if (state == null) {
            return;
        }
        Instant lastDebateSignal = state.lastDebateTurnAt.isAfter(state.lastEnvelopeAt)
                ? state.lastDebateTurnAt : state.lastEnvelopeAt;
        if (Duration.between(lastDebateSignal, now).compareTo(DEBATE_QUIESCENT_CLOSE) < 0) {
            return;
        }
        DebateTopic topic = DebateFocusResolver.focus(debateStore, review.id()).orElse(null);
        if (topic == null) {
            return;
        }
        if (hasPendingForTopic(review, attemptNo, topic.id())) {
            return;
        }
        List<DebateTurn> turns = debateStore.findTurns(review.id(), topic.id());
        boolean hasTurns = !turns.isEmpty();
        boolean hasRebuttal = turns.stream().anyMatch(turn -> turn.turnType() == DebateTurnType.REBUTTAL);
        boolean noSupportClaim = topic.claimIds().stream()
                .map(claimId -> debateStore.findClaim(review.id(), claimId).orElse(null))
                .filter(Objects::nonNull)
                .noneMatch(claim -> claim.position() == ClaimPosition.SUPPORT
                        && claim.status() != ClaimStatus.WITHDRAWN);
        boolean challengeQueueEmpty = challengeQueueEmpty(review, topic, debateStore);
        if (!(hasTurns || noSupportClaim || challengeQueueEmpty)) {
            return;
        }
        int round = topic.currentRound() == 2 ? 2 : 1;
        DebateTopicStatus status = hasRebuttal ? DebateTopicStatus.RESOLVED : DebateTopicStatus.ESCALATED;
        String idempotency = "liveness-close:" + topic.id().value() + ":" + round;
        DebateService debateService = providerOrNull(debateServiceProvider);
        if (debateService == null) {
            LOGGER.warn("LIVENESS_AUTO_CLOSE_SKIPPED reviewId={} attemptNo={} topicId={} reason=SERVICE_MISSING",
                    review.id().value(), attemptNo, topic.id().value());
            return;
        }
        try {
            synchronized (review) {
                debateService.closeTopic(review, new DebateToolCommands.CloseTopic(
                        new ReviewCommandMetadata(review.id(), review.version(),
                                new IdempotencyKey(idempotency)),
                        topic.id(),
                        status,
                        "服务端确定性收口：队列完成或静默超时，自动关闭焦点议题。"));
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("LIVENESS_AUTO_CLOSE_FAILED reviewId={} attemptNo={} topicId={} status={} error={}",
                    review.id().value(), attemptNo, topic.id().value(), status, exception.toString());
            return;
        }
        LOGGER.info("LIVENESS_AUTO_CLOSE_TOPIC reviewId={} attemptNo={} topicId={} status={}",
                review.id().value(), attemptNo, topic.id().value(), status);
        if (debateStore.findTopics(review.id()).stream().allMatch(t -> t.status().isTerminal())) {
            try {
                synchronized (review) {
                    debateService.beginJudging(review);
                }
                LOGGER.info("LIVENESS_AUTO_JUDGING reviewId={} attemptNo={}",
                        review.id().value(), attemptNo);
            } catch (RuntimeException exception) {
                LOGGER.warn("LIVENESS_AUTO_BEGIN_JUDGING_FAILED reviewId={} attemptNo={} error={}",
                        review.id().value(), attemptNo, exception.toString());
            }
        }
    }

    private boolean hasPendingForTopic(Review review, int attemptNo, TopicId topicId) {
        ReviewDispatchStore dispatchStore = providerOrNull(dispatchStoreProvider);
        if (dispatchStore == null) {
            return false;
        }
        return dispatchStore.findPending(review.id(), attemptNo).stream()
                .anyMatch(command -> topicId.equals(command.topicId()));
    }

    /** [AIREVIEW-PLAN-076#3] 质询角色队列：挂载序 OPPOSE 非 WITHDRAWN 角色 distinct，且排除最高严重度 SUPPORT 自身角色。 */
    private boolean challengeQueueEmpty(Review review, DebateTopic topic, ReviewDebateStore debateStore) {
        Claim supportTarget = null;
        for (ClaimId claimId : topic.claimIds()) {
            Claim candidate = debateStore.findClaim(review.id(), claimId).orElse(null);
            if (candidate == null || candidate.position() != ClaimPosition.SUPPORT
                    || candidate.status() == ClaimStatus.WITHDRAWN) {
                continue;
            }
            if (supportTarget == null || candidate.severity().ordinal() < supportTarget.severity().ordinal()) {
                supportTarget = candidate;
            }
        }
        RoleType excludedRole = supportTarget == null ? null : supportTarget.roleType();
        return topic.claimIds().stream()
                .map(claimId -> debateStore.findClaim(review.id(), claimId).orElse(null))
                .filter(Objects::nonNull)
                .filter(claim -> claim.position() == ClaimPosition.OPPOSE
                        && claim.status() != ClaimStatus.WITHDRAWN)
                .map(Claim::roleType)
                .distinct()
                .noneMatch(role -> excludedRole == null || role != excludedRole);
    }

    private static boolean isDebateTurnEvent(ReviewEventType type) {
        return type == ReviewEventType.CHALLENGE_SUBMITTED
                || type == ReviewEventType.REBUTTAL_SUBMITTED
                || type == ReviewEventType.CLAIM_SUBMITTED;
    }

    private static boolean isDispatchEnvelopeEvent(ReviewEventType type) {
        return type == ReviewEventType.DISPATCH_COMMAND_ISSUED
                || type == ReviewEventType.DISPATCH_COMMAND_CONSUMED
                || type == ReviewEventType.DISPATCH_COMMAND_EXPIRED
                || type == ReviewEventType.DISPATCH_COMMAND_REJECTED;
    }

    /** [AIREVIEW-PLAN-060#3] 确定性收口入口；key 已在调用前从 states 移除。 */
    private void converge(Review review, ReviewStage stage) {
        switch (stage) {
            case CONFLICT_DETECTION -> forceConflictDetection(review);
            case JUDGING -> forceJudging(review);
            case INITIAL_REVIEW -> forceInitialReview(review);
            default -> LOGGER.warn("LIVENESS_FORCE_SKIPPED reviewId={} attemptNo={} stage={} reason=UNCOVERED_STAGE",
                    review.id().value(), review.attemptNo(), stage);
        }
    }

    /**
     * [AIREVIEW-PLAN-060#3] CONFLICT_DETECTION 收口：确定性召回候选，镜像
     * ListConflictCandidatesTool/RegisterTopicsTool 的 subjectKey/claimIds/publicTitle 取值规则批量
     * 注册议题；无候选则走 skipDebateWhenNoConflicts。
     */
    private void forceConflictDetection(Review review) {
        ConflictDetectionService conflictDetectionService = providerOrNull(conflictDetectionServiceProvider);
        DebateService debateService = providerOrNull(debateServiceProvider);
        if (conflictDetectionService == null || debateService == null) {
            LOGGER.warn("LIVENESS_FORCE_SKIPPED reviewId={} attemptNo={} stage=CONFLICT_DETECTION reason=SERVICE_MISSING",
                    review.id().value(), review.attemptNo());
            return;
        }
        synchronized (review) {
            if (review.stage() != ReviewStage.CONFLICT_DETECTION) {
                return;
            }
            ConflictDetectionService.Outcome outcome = conflictDetectionService.detect(review);
            List<DebateToolCommands.TopicProposal> proposals = outcome.result().candidates().stream()
                    .map(candidate -> new DebateToolCommands.TopicProposal(
                            candidate.subjectKey(),
                            candidate.claimIds(),
                            livenessPublicTitle(candidate.subjectKey())))
                    .toList();
            if (proposals.isEmpty()) {
                debateService.skipDebateWhenNoConflicts(review);
                LOGGER.warn("LIVENESS_FORCE_SKIP_DEBATE reviewId={} attemptNo={} candidates=0",
                        review.id().value(), review.attemptNo());
                return;
            }
            debateService.registerTopics(review, new DebateToolCommands.RegisterTopics(
                    new ReviewCommandMetadata(review.id(), review.version(),
                            new IdempotencyKey(LIVENESS_REGISTER_IDEMPOTENCY_PREFIX + review.id().value())),
                    RoleType.DIRECTOR,
                    proposals));
            LOGGER.warn("LIVENESS_FORCE_REGISTER_TOPICS reviewId={} attemptNo={} topics={}",
                    review.id().value(), review.attemptNo(), proposals.size());
        }
    }

    /** [AIREVIEW-PLAN-060#3] JUDGING 收口：确定性草稿 Gate；已有草稿时 JudgeService 幂等返回并 remove(key)。 */
    private void forceJudging(Review review) {
        JudgeService judgeService = providerOrNull(judgeServiceProvider);
        if (judgeService == null) {
            LOGGER.warn("LIVENESS_FORCE_SKIPPED reviewId={} attemptNo={} stage=JUDGING reason=SERVICE_MISSING",
                    review.id().value(), review.attemptNo());
            return;
        }
        synchronized (review) {
            if (review.stage() != ReviewStage.JUDGING) {
                return;
            }
            judgeService.draftGate(review);
            LOGGER.warn("LIVENESS_FORCE_JUDGING reviewId={} attemptNo={} stageAfter={}",
                    review.id().value(), review.attemptNo(), review.stage());
        }
    }

    /** [AIREVIEW-PLAN-060#3] INITIAL_REVIEW 收口：走 ReviewCommandService 公共 failReview 路径（与 263 行同口径）。 */
    private void forceInitialReview(Review review) {
        ReviewCommandService commandService = providerOrNull(reviewCommandServiceProvider);
        if (commandService == null) {
            LOGGER.warn("LIVENESS_FORCE_SKIPPED reviewId={} attemptNo={} stage=INITIAL_REVIEW reason=SERVICE_MISSING",
                    review.id().value(), review.attemptNo());
            return;
        }
        String incompleteRoles = review.roleActivations().stream()
                .filter(activation -> !activation.initialReviewCompleted())
                .map(activation -> activation.roleType().name())
                .sorted()
                .collect(Collectors.joining(","));
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        ReviewRuntimeTraceRegistry traceRegistry = providerOrNull(traceRegistryProvider);
        Instant lastTraceActivity = traceRegistry == null
                ? Instant.EPOCH
                : traceRegistry.lastObservedAt(runtimeId).orElse(Instant.EPOCH);
        boolean failed;
        synchronized (review) {
            failed = commandService.failReview(review,
                    "LIVENESS_TIMEOUT: 初审活性超时，未完成角色=[" + incompleteRoles + "]");
        }
        if (failed) {
            LOGGER.warn("LIVENESS_FORCE_FAIL reviewId={} attemptNo={} incompleteRoles={} lastTraceActivity={}",
                    review.id().value(), review.attemptNo(), incompleteRoles, lastTraceActivity);
        } else {
            LOGGER.info("LIVENESS_FORCE_FAIL_SKIPPED reviewId={} attemptNo={} reason=NOT_FAILABLE stage={}",
                    review.id().value(), review.attemptNo(), review.stage());
        }
    }

    /**
     * [AIREVIEW-PLAN-060#3] 复刻 ReviewDebateToolFactory.RegisterTopicsTool 的 publicTitle 边界规则：
     * 可选展示标题，在工具边界截断为 200 字符；服务端收口路径无模型输入，按 subjectKey 生成确定性标题。
     */
    private static String livenessPublicTitle(String subjectKey) {
        String title = "活性收口议题：" + subjectKey;
        return title.length() > 200 ? title.substring(0, 200) : title;
    }

    private static <T> T providerOrNull(ObjectProvider<T> provider) {
        return provider == null ? null : provider.getIfAvailable();
    }

    private static boolean isCoveredStage(ReviewStage stage) {
        return stage == ReviewStage.INITIAL_REVIEW
                || stage == ReviewStage.CONFLICT_DETECTION
                || stage == ReviewStage.JUDGING;
    }

    /**
     * [AIREVIEW-PLAN-069#5] 终态判定：显式取消/失败事件，或成功完成路径的 COMPLETED/NOTIFYING 阶段事实。
     */
    private static boolean isTerminalEvent(ReviewEvent event) {
        return event.type() == ReviewEventType.REVIEW_CANCELLED
                || event.type() == ReviewEventType.REVIEW_FAILED
                || event.stage() == ReviewStage.COMPLETED
                || event.stage() == ReviewStage.NOTIFYING;
    }

    /**
     * [AIREVIEW-PLAN-069#5] 终态统一清理入口（dispatcher 的 COMPLETED 观测调用）：移除 attempt 活性
     * 状态，同时清空补偿重投计数。幂等：已清理的 attempt 再次调用无副作用。
     */
    public void clear(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        String key = key(reviewId, attemptNo);
        if (states.remove(key) != null) {
            LOGGER.info("LIVENESS_STATE_CLEARED reviewId={} attemptNo={} eventType=TERMINAL_CLEANUP",
                    reviewId.value(), attemptNo);
        }
        clearRedeliveries();
    }

    /** [AIREVIEW-PLAN-069#5] 终态/超限时整体清空补偿重投计数（commandId 为 UUID，无需按 attempt 归并）。 */
    private void clearRedeliveries() {
        if (redeliveries.isEmpty()) {
            return;
        }
        LOGGER.info("LIVENESS_REDELIVERY_COUNTERS_CLEARED tracked={}", redeliveries.size());
        redeliveries.clear();
    }

    private static String key(ReviewId reviewId, int attemptNo) {
        return reviewId.value() + ":" + attemptNo;
    }

    /** [AIREVIEW-PLAN-060#2] 标签口径与 ReviewWorkflowDispatcher 一致。 */
    private static String directorLabel(String runtimeId) {
        return runtimeId + "-director";
    }

    /** [AIREVIEW-PLAN-060#2] 角色标签口径与 ReviewWorkflowDispatcher 一致。 */
    private static String roleLabel(String runtimeId, RoleType roleType) {
        return runtimeId + "-" + roleType.name().toLowerCase(Locale.ROOT);
    }

    private void send(AgentRuntimeAdapter adapter, String runtimeId, String recipient, String message) {
        try {
            adapter.send(runtimeId, recipient, message)
                    .onErrorResume(exception -> {
                        LOGGER.warn("LIVENESS_REWAKE_SEND_FAILED runtimeId={} recipient={} error={}",
                                runtimeId, recipient, exception.toString());
                        return reactor.core.publisher.Mono.empty();
                    })
                    .subscribe();
        } catch (RuntimeException exception) {
            LOGGER.warn("LIVENESS_REWAKE_SEND_FAILED runtimeId={} recipient={} error={}",
                    runtimeId, recipient, exception.toString());
        }
    }

    /** Per-attempt heartbeat state; one per reviewId:attemptNo. */
    private static final class LivenessState {

        private volatile Instant lastActivityAt = Instant.now();
        // [AIREVIEW-PLAN-076#3] 辩论静默关题观察窗：EPOCH 默认保证首次扫描不会立即关题。
        private volatile Instant lastDebateTurnAt = Instant.EPOCH;
        private volatile Instant lastEnvelopeAt = Instant.EPOCH;
        private volatile ReviewStage stage;
        private final ConcurrentMap<ReviewStage, AtomicInteger> rewakes = new ConcurrentHashMap<>();
    }
}
