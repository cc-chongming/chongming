package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.DebateConvergenceGuard;
import ai.cc.chongming.review.application.DebateFocusResolver;
import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.DirectorPlanRevisionPromoter;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.application.ReviewDispatchService;
import ai.cc.chongming.review.application.ReviewEventListener;
import ai.cc.chongming.review.application.ReviewLivenessGuard;
import ai.cc.chongming.review.application.ReviewOrchestrationService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.ReviewRuntimeTraceRegistry;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * [AIREVIEW-PLAN-009#1.4][AIREVIEW-PLAN-024#方案3] Delivers only server-verified directed
 * dispatch envelopes after committed business events. The former broadcast of generic debate
 * prompts to every role is removed: the Director issues dispatch commands through validated
 * server tools, and this dispatcher injects the exact same envelope into the target role's
 * context. It never changes review state itself.
 *
 * @author wangli
 */
@Component
public class ReviewWorkflowDispatcher implements ReviewEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewWorkflowDispatcher.class);

    /** How long the server-generated rebuttal envelope stays consumable after a challenge. */
    private static final Duration REBUTTAL_DISPATCH_TTL = Duration.ofMinutes(10);

    /**
     * [AIREVIEW-PLAN-046#1] How long a server-generated CHALLENGE envelope stays consumable after a
     * topic opens with both sides or a defence SUPPORT claim lands. 20 minutes deliberately exceeds
     * the rebuttal TTL so objectors have room to interrogate before the server reclaims the envelope.
     */
    private static final Duration CHALLENGE_DISPATCH_TTL = Duration.ofMinutes(20);

    /** [AIREVIEW-PLAN-046#1] Server challenge against a SUPPORT claim mounted before the topic opened. */
    private static final String SERVER_CHALLENGE_AFTER_OPPOSITION = "SERVER_CHALLENGE_AFTER_OPPOSITION";

    /** [AIREVIEW-PLAN-046#1] Server challenge after a defence SUPPORT claim completed both sides. */
    private static final String SERVER_CHALLENGE_AFTER_DEFENSE = "SERVER_CHALLENGE_AFTER_DEFENSE";

    /** [AIREVIEW-PLAN-059#4] Server challenge re-armed when the serial focus advances to a two-sided never-challenged topic. */
    private static final String SERVER_CHALLENGE_ON_FOCUS_ADVANCE = "SERVER_CHALLENGE_ON_FOCUS_ADVANCE";

    /** [AIREVIEW-PLAN-076#2] 质询队列在答辩提交后推进：完成一个 OPPOSE 角色的 REBUTTAL 即放行下一角色。 */
    private static final String SERVER_CHALLENGE_AFTER_REBUTTAL = "SERVER_CHALLENGE_AFTER_REBUTTAL";

    /** [AIREVIEW-PLAN-076#2] 信封被消费后立即推进同议题质询队列。 */
    private static final String SERVER_CHALLENGE_AFTER_COMMAND_CONSUMED = "SERVER_CHALLENGE_AFTER_COMMAND_CONSUMED";

    /** [AIREVIEW-PLAN-076#2] 信封过期后立即推进同议题质询队列。 */
    private static final String SERVER_CHALLENGE_AFTER_COMMAND_EXPIRED = "SERVER_CHALLENGE_AFTER_COMMAND_EXPIRED";

    /** [AIREVIEW-PLAN-076#2] 信封被拒绝后立即推进同议题质询队列。 */
    private static final String SERVER_CHALLENGE_AFTER_COMMAND_REJECTED = "SERVER_CHALLENGE_AFTER_COMMAND_REJECTED";

    /** [AIREVIEW-PLAN-076#2] 议题进入第二轮后为该轮放行首个尚未质询的 OPPOSE 角色。 */
    private static final String SERVER_CHALLENGE_AFTER_ROUND_2_STARTED = "SERVER_CHALLENGE_AFTER_ROUND_2_STARTED";

    /** [AIREVIEW-PLAN-075#3] DEBATE 阶段相同唤醒文案的重复 wakeDirector 冷却窗口。 */
    private static final Duration COORDINATOR_WAKE_COOLDOWN = Duration.ofSeconds(60);

    private final ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider;
    private final ReviewRegistry reviewRegistry;
    private final ReviewDispatchService dispatchService;
    private final ReviewDebateStore debateStore;
    private final ObjectProvider<DebateConvergenceGuard> convergenceGuardProvider;
    private final AgentScopeProperties properties;
    private final ObjectProvider<ReviewLivenessGuard> livenessGuardProvider;
    private final ObjectProvider<DirectorPlanRevisionPromoter> planPromoterProvider;
    private final ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider;
    private final ObjectProvider<ReviewOrchestrationService> orchestrationProvider;
    // [AIREVIEW-PLAN-084#1] 末题关闭即进裁决；ObjectProvider 防与 DebateService 事件构造循环。
    private final ObjectProvider<DebateService> debateServiceProvider;
    // [AIREVIEW-PLAN-085#1] 末裁即拟门禁；ObjectProvider 防与 JudgeService 事件构造循环。
    private final ObjectProvider<JudgeService> judgeServiceProvider;
    private final ConcurrentMap<String, reactor.core.publisher.Sinks.Many<Dispatch>> queues = new ConcurrentHashMap<>();
    // [AIREVIEW-PLAN-075#3] per-attempt wake 冷却状态：lastTurnEventAt / lastWakeAt / 上次文案 hash。
    private final ConcurrentMap<String, WakeCooldownState> wakeCooldowns = new ConcurrentHashMap<>();

    public ReviewWorkflowDispatcher(ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider) {
        this(runtimeAdapterProvider, null, null, null, null, null);
    }

    public ReviewWorkflowDispatcher(
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ReviewRegistry reviewRegistry,
            ReviewDispatchService dispatchService,
            ReviewDebateStore debateStore) {
        this(runtimeAdapterProvider, reviewRegistry, dispatchService, debateStore, null, null);
    }

    public ReviewWorkflowDispatcher(
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ReviewRegistry reviewRegistry,
            ReviewDispatchService dispatchService,
            ReviewDebateStore debateStore,
            ObjectProvider<DebateConvergenceGuard> convergenceGuardProvider,
            AgentScopeProperties properties) {
        this(runtimeAdapterProvider, reviewRegistry, dispatchService, debateStore, convergenceGuardProvider,
                properties, null, null, null, null);
    }

    /**
     * [AIREVIEW-PLAN-069#4][AIREVIEW-PLAN-084#1][AIREVIEW-PLAN-085#1] Compatibility constructor used
     * by older tests; the DebateService and JudgeService providers are absent, so terminal-close
     * auto-judging and last-judgement gate drafting are disabled for those call sites.
     */
    public ReviewWorkflowDispatcher(
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ReviewRegistry reviewRegistry,
            ReviewDispatchService dispatchService,
            ReviewDebateStore debateStore,
            ObjectProvider<DebateConvergenceGuard> convergenceGuardProvider,
            AgentScopeProperties properties,
            ObjectProvider<ReviewLivenessGuard> livenessGuardProvider,
            ObjectProvider<DirectorPlanRevisionPromoter> planPromoterProvider,
            ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider,
            ObjectProvider<ReviewOrchestrationService> orchestrationProvider) {
        this(runtimeAdapterProvider, reviewRegistry, dispatchService, debateStore, convergenceGuardProvider,
                properties, livenessGuardProvider, planPromoterProvider, traceRegistryProvider,
                orchestrationProvider, null);
    }

    /**
     * [AIREVIEW-PLAN-084#1] Compatibility constructor used by older tests; the JudgeService
     * provider is absent, so last-judgement gate drafting is disabled for those call sites.
     */
    public ReviewWorkflowDispatcher(
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ReviewRegistry reviewRegistry,
            ReviewDispatchService dispatchService,
            ReviewDebateStore debateStore,
            ObjectProvider<DebateConvergenceGuard> convergenceGuardProvider,
            AgentScopeProperties properties,
            ObjectProvider<ReviewLivenessGuard> livenessGuardProvider,
            ObjectProvider<DirectorPlanRevisionPromoter> planPromoterProvider,
            ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider,
            ObjectProvider<ReviewOrchestrationService> orchestrationProvider,
            ObjectProvider<DebateService> debateServiceProvider) {
        this(runtimeAdapterProvider, reviewRegistry, dispatchService, debateStore, convergenceGuardProvider,
                properties, livenessGuardProvider, planPromoterProvider, traceRegistryProvider,
                orchestrationProvider, debateServiceProvider, null);
    }

    /**
     * [AIREVIEW-PLAN-069#4][AIREVIEW-PLAN-084#1][AIREVIEW-PLAN-085#1] Canonical Spring constructor.
     * Terminal-state cleanup, last-close auto-judging and last-judgement gate drafting dependencies
     * are injected as lazy {@link ObjectProvider}s so the dispatcher never hard-depends on beans that
     * may themselves receive review events (no construction cycle).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewWorkflowDispatcher(
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ReviewRegistry reviewRegistry,
            ReviewDispatchService dispatchService,
            ReviewDebateStore debateStore,
            ObjectProvider<DebateConvergenceGuard> convergenceGuardProvider,
            AgentScopeProperties properties,
            ObjectProvider<ReviewLivenessGuard> livenessGuardProvider,
            ObjectProvider<DirectorPlanRevisionPromoter> planPromoterProvider,
            ObjectProvider<ReviewRuntimeTraceRegistry> traceRegistryProvider,
            ObjectProvider<ReviewOrchestrationService> orchestrationProvider,
            ObjectProvider<DebateService> debateServiceProvider,
            ObjectProvider<JudgeService> judgeServiceProvider) {
        this.runtimeAdapterProvider = Objects.requireNonNull(runtimeAdapterProvider, "runtimeAdapterProvider must not be null");
        this.reviewRegistry = reviewRegistry;
        this.dispatchService = dispatchService;
        this.debateStore = debateStore;
        this.convergenceGuardProvider = convergenceGuardProvider;
        this.properties = properties;
        this.livenessGuardProvider = livenessGuardProvider;
        this.planPromoterProvider = planPromoterProvider;
        this.traceRegistryProvider = traceRegistryProvider;
        this.orchestrationProvider = orchestrationProvider;
        this.debateServiceProvider = debateServiceProvider;
        this.judgeServiceProvider = judgeServiceProvider;
    }

    /** [AIREVIEW-PLAN-059#4] properties 缺失（旧构造/测试缝）时按配置默认值视为串行开启。 */
    private boolean serialEnabled() {
        return properties == null || properties.debateSerialTopics();
    }

    /** [AIREVIEW-PLAN-059#4] 当前焦点议题 id 文本；无焦点或 store 缺失时为 "-"。 */
    private String focusIdOf(ReviewEvent event) {
        if (debateStore == null) {
            return "-";
        }
        return DebateFocusResolver.focus(debateStore, event.reviewId())
                .map(topic -> topic.id().value().toString()).orElse("-");
    }

    @Override
    public void onCommitted(ReviewEvent event) {
        // [AIREVIEW-PLAN-075#3] 记录 per-attempt 的辩论回合事件时间，供 DEBATE 阶段 wakeDirector 冷却判断。
        recordTurnActivity(event);
        // [AIREVIEW-PLAN-069#4] 成功完成路径没有 REVIEW_COMPLETED 事件类型（已核对枚举）；真实收口事实
        // 是 stage 已进入 COMPLETED 的事件（如 NOTIFICATION_SENT：markDelivered 先 transitionTo(COMPLETED)
        // 再发布）。该事件触发一次统一终态清理并结束分发，避免 per-attempt 的 sink/订阅/跟踪永久驻留。
        if (event.stage() == ReviewStage.COMPLETED) {
            cleanupTerminalAttempt(event, "COMPLETED");
            return;
        }
        if (event.type() == ReviewEventType.REVIEW_CANCELLED || event.type() == ReviewEventType.REVIEW_FAILED) {
            rejectPendingCommands(event, "REVIEW_TERMINATED");
            cleanupTerminalAttempt(event, event.type().name());
        } else if (event.type() == ReviewEventType.INITIAL_REVIEW_COMPLETED) {
            // [AIREVIEW-PLAN-024#方案4] Conflict detection is deterministic: recall candidates,
            // batch-register every chosen subject in one command, or skip when none remains.
            send(runtimeId(event), directorLabel(event), "All core initial reviews are complete. First call list_persisted_claims, then list_conflict_candidates. If at least one conflict candidate remains, register ALL chosen candidates in one register_topics batch command; only when no conflict candidate remains call skip_debate_when_no_conflicts. A single GAP or UNKNOWN assessment alone is never a debate topic. Do not search the workspace for Claim files or create facts in text.");
        } else if (event.type() == ReviewEventType.DEBATE_TOPIC_OPENED) {
            // [AIREVIEW-PLAN-046#1] Opening a two-sided topic is the first server-side challenge
            // trigger; the Director wake below states that challenges are server-issued.
            // [AIREVIEW-PLAN-076#2] 开题只放行队列中第一个尚未质询的 OPPOSE 角色。
            advanceChallengeQueue(event, event.topicId(), SERVER_CHALLENGE_AFTER_OPPOSITION);
            // [AIREVIEW-PLAN-059#4] 串行辩论：唤醒附当前焦点，协调者仅驱动焦点议题。
            wakeDirector(event, "A debate topic opened. Direct the debate exclusively through dispatch_debate_action: issue one directed dispatch command per intended write action (recipientRole, allowedAction, topicId, and the target Claim or Turn). The server validates and delivers each envelope; never instruct roles with free text and never grant an action beyond one command. challenges are server-issued; do not dispatch CHALLENGE yourself."
                    + " （串行辩论）当前焦点议题=" + focusIdOf(event)
                    + "：仅为焦点议题签发 DEFENSE 等调度命令；其余议题服务端排队，焦点终态后自动前进。");
        } else if (event.type() == ReviewEventType.CLAIM_SUBMITTED) {
            // [AIREVIEW-PLAN-046#1] A SUPPORT claim committed during a debate round can complete the
            // defence side of an objector-only topic; the server then issues the CHALLENGE envelopes.
            issueChallengeAfterDefenseClaim(event);
        } else if (event.type() == ReviewEventType.DEBATE_ROUND_2_STARTED) {
            // [AIREVIEW-PLAN-047#1] Round two is topic-level: only the named topic advances; the
            // review stays in the single DEBATE phase while every other topic keeps its own round.
            // [AIREVIEW-PLAN-076#2] 议题进入第二轮后，放行该轮首个尚未质询的 OPPOSE 角色。
            advanceChallengeQueue(event, event.topicId(), SERVER_CHALLENGE_AFTER_ROUND_2_STARTED);
            wakeDirector(event, "Debate round two started only for topic "
                    + (event.topicId() == null ? "-" : event.topicId().value())
                    + " (仅该议题进入第二轮，其余议题保持各自轮次，评审仍处于 DEBATE 阶段). "
                    + "Issue dispatch_debate_action commands for every still-required round-two action on that "
                    + "topic with the matching targets, or close it with close_debate_topic and converge with "
                    + "begin_judging when nothing further is required. Do not run an empty round.");
        } else if (event.type() == ReviewEventType.DEBATE_TOPIC_CLOSED) {
            // [AIREVIEW-PLAN-064#2] 服务端收敛可能已先行把评审推进到裁决阶段；此时迟到的议题关闭事件
            // 不应再唤醒辩论动作（原文案/焦点前进/质询补发全部跳过），只通知 Director 收手。
            Review current = reviewRegistry == null ? null
                    : reviewRegistry.find(event.reviewId())
                            .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                            .orElse(null);
            if (current != null && !isDebateStage(current.stage())) {
                wakeDirector(event, "评审已进入裁决阶段（服务端收敛先行完成），本轮无需任何动作，不要再次调用 begin_judging。");
            } else {
                // [AIREVIEW-PLAN-059#4] 焦点前进：唤醒附下一焦点议题；新焦点双方齐备但从未质询时补发质询。
                Optional<DebateTopic> nextFocus = debateStore == null
                        ? Optional.empty() : DebateFocusResolver.focus(debateStore, event.reviewId());
                String focusText = nextFocus.map(topic -> "下一焦点议题=" + topic.id().value()
                        + "，请围绕它继续辩论（为它签发 DEFENSE 等调度）；其余议题保持排队。 ")
                        .orElse("所有议题已终态，使用 begin_judging。 ");
                wakeDirector(event, "A debate topic was closed. " + focusText
                        + "If more topics still need their second round, open it per topic with begin_second_round(topicId) (议题级，仅该议题进入第二轮); when every topic is terminal, use begin_judging.");
                nextFocus.ifPresent(topic -> advanceChallengeQueue(event, topic.id(), SERVER_CHALLENGE_ON_FOCUS_ADVANCE));
            }
            // [AIREVIEW-PLAN-084#1] 末题关闭即确定性进裁决：wake 之后若 store 内全部议题已终态，
            // 直接 resolve DebateService 调用 beginJudging；DebateService 自身的 064 幂等守卫保证重放安全。
            if (current != null && debateStore != null
                    && debateStore.findTopics(current.id()).stream()
                            .allMatch(topic -> topic.status().isTerminal())) {
                DebateService debateService = debateServiceProvider == null
                        ? null : debateServiceProvider.getIfAvailable();
                if (debateService != null) {
                    try {
                        debateService.beginJudging(current);
                        LOGGER.info("BEGIN_JUDGING_ON_LAST_CLOSE reviewId={} attemptNo={}",
                                current.id().value(), current.attemptNo());
                    } catch (RuntimeException exception) {
                        LOGGER.warn("BEGIN_JUDGING_ON_LAST_CLOSE_FAILED reviewId={} attemptNo={}",
                                current.id().value(), current.attemptNo(), exception);
                    }
                }
            }
        } else if (event.type() == ReviewEventType.CHALLENGE_SUBMITTED) {
            issueRebuttalDispatch(event);
            wakeDirector(event, "A debate turn was committed. Review the public context and decide whether to close the topic, begin_second_round(topicId) for that topic alone, or continue the bounded debate.");
        } else if (event.type() == ReviewEventType.REBUTTAL_SUBMITTED) {
            // [AIREVIEW-PLAN-076#2] 一条答辩提交后，质询队列推进到下一位尚未质询的 OPPOSE 角色。
            advanceChallengeQueue(event, event.topicId(), SERVER_CHALLENGE_AFTER_REBUTTAL);
            wakeDirector(event, "A debate turn was committed. Review the public context and decide whether to close the topic, begin_second_round(topicId) for that topic alone, or continue the bounded debate.");
        } else if (event.type() == ReviewEventType.POSITION_CHANGED
                || event.type() == ReviewEventType.EVIDENCE_REQUESTED) {
            wakeDirector(event, "A debate turn was committed. Review the public context and decide whether to close the topic, begin_second_round(topicId) for that topic alone, or continue the bounded debate.");
        } else if (event.type() == ReviewEventType.DISPATCH_COMMAND_ISSUED) {
            deliverDispatchEnvelope(event);
        } else if (event.type() == ReviewEventType.DISPATCH_COMMAND_CONSUMED) {
            // [AIREVIEW-PLAN-076#2] 一条信封被消费后，其角色已完成本轮质询，推进到下一位角色。
            advanceChallengeQueue(event, event.topicId(), SERVER_CHALLENGE_AFTER_COMMAND_CONSUMED);
        } else if (event.type() == ReviewEventType.DISPATCH_COMMAND_EXPIRED
                || event.type() == ReviewEventType.DISPATCH_COMMAND_REJECTED) {
            // [AIREVIEW-PLAN-076#2] 过期/拒绝的信封不再阻塞队列：下一触发点放行下一位角色。
            advanceChallengeQueue(event, event.topicId(),
                    event.type() == ReviewEventType.DISPATCH_COMMAND_EXPIRED
                            ? SERVER_CHALLENGE_AFTER_COMMAND_EXPIRED
                            : SERVER_CHALLENGE_AFTER_COMMAND_REJECTED);
            LOGGER.info("REVIEW_DISPATCH_COMMAND_DROPPED reviewId={} type={} commandId={} reason={}",
                    event.reviewId().value(), event.type(),
                    event.payload().getOrDefault("commandId", "-"),
                    event.payload().getOrDefault("reason", "-"));
            wakeDirector(event, "Dispatch command "
                    + event.payload().getOrDefault("commandId", "-") + " ("
                    + event.payload().getOrDefault("allowedAction", "-") + " for "
                    + event.payload().getOrDefault("recipientRole", "-") + ") was dropped: "
                    + event.payload().getOrDefault("reason", event.type().name())
                    + ". Reissue a valid dispatch_debate_action command or converge with the stage tools.");
        } else if (event.type() == ReviewEventType.JUDGING_STARTED) {
            clearGuard(event);
            // The debate is over; stop any role subagent still grinding through its dispatched run
            // so it stops producing output (and rejected turns) during judging / human decision.
            AgentRuntimeAdapter adapter = runtimeAdapterProvider.getIfAvailable();
            if (adapter != null) {
                adapter.stopRoleRuns(runtimeId(event)).subscribe();
            }
            rejectPendingCommands(event, "JUDGING_STARTED");
            dispatchJudgeForEvent(event);
        } else if (event.type() == ReviewEventType.DEBATE_SKIPPED) {
            clearGuard(event);
            dispatchJudgeForEvent(event);
        } else if (event.type() == ReviewEventType.JUDGEMENT_SUBMITTED) {
            // [AIREVIEW-PLAN-085#1] 末条裁决提交即服务器确定性拟 Gate 草案；幂等守卫在 JudgeService。
            draftGateOnLastJudgement(event);
        }
    }

    /**
     * [AIREVIEW-PLAN-085#1] JUDGEMENT_SUBMITTED 收口：仅在评审处于 JUDGING、每个议题都已有
     * Judge decision 且尚无 Gate 草案时，经懒 ObjectProvider 调用 JudgeService.draftGate。
     * 任何一条裁决提交都可命中该分支，但只有末条裁决会让 allMatch 成立，天然只触发一次。
     */
    private void draftGateOnLastJudgement(ReviewEvent event) {
        Review review = reviewRegistry == null ? null
                : reviewRegistry.find(event.reviewId())
                        .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                        .orElse(null);
        if (review == null || debateStore == null || review.stage() != ReviewStage.JUDGING) {
            return;
        }
        List<DebateTopic> topics = debateStore.findTopics(review.id());
        boolean everyTopicJudged = topics.stream()
                .allMatch(topic -> debateStore.findJudgeDecision(review.id(), topic.id()).isPresent());
        if (!everyTopicJudged || debateStore.findGateDraft(review.id()).isPresent()) {
            return;
        }
        JudgeService judgeService = judgeServiceProvider == null
                ? null : judgeServiceProvider.getIfAvailable();
        if (judgeService == null) {
            return;
        }
        try {
            judgeService.draftGate(review);
            // [AIREVIEW-PLAN-085#2] 成功日志记录末裁触发拟门禁的事实。
            LOGGER.info("GATE_DRAFT_ON_LAST_JUDGEMENT reviewId={} attemptNo={}",
                    review.id().value(), review.attemptNo());
        } catch (RuntimeException exception) {
            LOGGER.warn("GATE_DRAFT_ON_LAST_JUDGEMENT_FAILED reviewId={} attemptNo={}",
                    review.id().value(), review.attemptNo(), exception);
        }
    }

    public void dispatchJudge(Review review) {
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        send(runtimeId, roleLabel(runtimeId, RoleType.JUDGE),
                "All debate topics are terminal. Use submit_judgement for each topic; if the topic list is empty, skip it. Then always call draft_gate exactly once so the judging stage can finish. Do not add facts.");
    }

    /**
     * [AIREVIEW-PLAN-024#方案4 收口] Single Judge wake point for every path into judging: the
     * Director's begin_judging/skip tools and the server-side forced convergence all publish
     * JUDGING_STARTED or DEBATE_SKIPPED, so the Judge can never be left idle in JUDGING.
     */
    private void dispatchJudgeForEvent(ReviewEvent event) {
        if (reviewRegistry == null) {
            return;
        }
        reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .ifPresent(this::dispatchJudge);
    }

    /**
     * After a committed challenge the server alone issues the rebuttal envelope, addressed to the
     * challenge's target role; other roles never see it and cannot rebut in its place.
     */
    private void issueRebuttalDispatch(ReviewEvent event) {
        if (dispatchService == null || reviewRegistry == null || debateStore == null || event.turnId() == null) {
            return;
        }
        Review review = reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .orElse(null);
        if (review == null) {
            LOGGER.warn("REBUTTAL_DISPATCH_SKIPPED reviewId={} reason=REVIEW_NOT_FOUND", event.reviewId().value());
            return;
        }
        DebateTurn challenge = debateStore.findTurn(event.reviewId(), event.turnId()).orElse(null);
        if (challenge == null || challenge.targetRole() == null) {
            LOGGER.warn("REBUTTAL_DISPATCH_SKIPPED reviewId={} turnId={} reason=CHALLENGE_TURN_NOT_FOUND",
                    event.reviewId().value(), event.turnId().value());
            return;
        }
        // [AIREVIEW-PLAN-059#4] 串行闸：非焦点议题不签发答辩信封。
        if (serialEnabled()) {
            Optional<DebateTopic> focus = DebateFocusResolver.focus(debateStore, review.id());
            if (focus.isPresent() && !focus.get().id().equals(challenge.topicId())) {
                LOGGER.info("REBUTTAL_DISPATCH_SKIPPED reviewId={} turnId={} reason=SERIAL_NOT_FOCUS focusTopicId={}",
                        event.reviewId().value(), event.turnId().value(), focus.get().id().value());
                return;
            }
        }
        try {
            synchronized (review) {
                dispatchService.issue(review, new ReviewDispatchService.DispatchProposal(
                        new ReviewCommandMetadata(review.id(), review.version(),
                                new IdempotencyKey("dispatch:rebuttal:" + challenge.turnId().value())),
                        challenge.targetRole(),
                        DispatchedAction.REBUTTAL,
                        challenge.round(),
                        challenge.topicId(),
                        null,
                        challenge.turnId(),
                        Instant.now().plus(REBUTTAL_DISPATCH_TTL),
                        RoleType.DIRECTOR,
                        "SERVER_REBUTTAL_AFTER_CHALLENGE"));
            }
        } catch (RuntimeException exception) {
            // A failed rebuttal issuance must not abort event delivery; the Director wake above
            // keeps the review moving and the log names the reason.
            LOGGER.warn("REBUTTAL_DISPATCH_ISSUE_FAILED reviewId={} turnId={}",
                    event.reviewId().value(), event.turnId().value(), exception);
        }
    }

    /**
     * [AIREVIEW-PLAN-046#1] CLAIM_SUBMITTED trigger of the unified server-side challenge dispatch:
     * only a SUPPORT claim committed during a debate round that completes the support side of an
     * objector-only topic (its claim is mounted on exactly one topic that had no SUPPORT before)
     * hands over to the shared {@link #advanceChallengeQueue(ReviewEvent, TopicId, String)} rule.
     */
    private void issueChallengeAfterDefenseClaim(ReviewEvent event) {
        if (dispatchService == null || reviewRegistry == null || debateStore == null || event.claimId() == null) {
            return;
        }
        // [AIREVIEW-PLAN-047#1] The single DEBATE phase (plus legacy round stages) is the trigger
        // window for server-side challenges after a defence SUPPORT claim lands.
        if (!isDebateStage(event.stage())) {
            return;
        }
        Claim submitted = debateStore.findClaim(event.reviewId(), event.claimId()).orElse(null);
        if (submitted == null || submitted.position() != ClaimPosition.SUPPORT) {
            return;
        }
        TopicId topicId = debateStore.findTopics(event.reviewId()).stream()
                .filter(topic -> topic.claimIds().contains(event.claimId()))
                .map(DebateTopic::id)
                .findFirst()
                .orElse(null);
        if (topicId == null) {
            LOGGER.warn("CHALLENGE_DISPATCH_SKIPPED reviewId={} claimId={} reason=TOPIC_WITH_CLAIM_NOT_FOUND",
                    event.reviewId().value(), event.claimId().value());
            return;
        }
        DebateTopic topic = debateStore.findTopic(event.reviewId(), topicId).orElse(null);
        if (topic == null) {
            LOGGER.warn("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=TOPIC_NOT_FOUND",
                    event.reviewId().value(), topicId.value());
            return;
        }
        // Only the claim that first completes the support side triggers the server dispatch; a later
        // defence claim on an already two-sided topic must not re-arm envelopes.
        boolean mountedSupportBefore = topic.claimIds().stream()
                .filter(claimId -> !claimId.equals(event.claimId()))
                .map(claimId -> debateStore.findClaim(event.reviewId(), claimId).orElse(null))
                .filter(Objects::nonNull)
                .anyMatch(claim -> claim.position() == ClaimPosition.SUPPORT
                        && claim.status() != ClaimStatus.WITHDRAWN);
        if (mountedSupportBefore) {
            LOGGER.info("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} claimId={} reason=SUPPORT_ALREADY_PRESENT",
                    event.reviewId().value(), topicId.value(), event.claimId().value());
            return;
        }
        advanceChallengeQueue(event, topicId, SERVER_CHALLENGE_AFTER_DEFENSE);
    }

    /**
     * [AIREVIEW-PLAN-076#1,#2] 统一质询队列入口：从业务事件解析 review/topic，先做阶段、终态与
     * 串行焦点闸门，再交给 {@link #advanceChallengeQueue(Review, DebateTopic, int, String)} 核心推进。
     * 取代 PLAN-046 的全扇出语义——同一轮每个 OPPOSE 角色依次放行，不再一次性向所有角色签发。
     */
    private void advanceChallengeQueue(ReviewEvent event, TopicId topicId, String reason) {
        if (dispatchService == null || reviewRegistry == null || debateStore == null || topicId == null) {
            return;
        }
        Review review = reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .orElse(null);
        if (review == null) {
            LOGGER.warn("CHALLENGE_QUEUE_SKIPPED reviewId={} topicId={} reason=REVIEW_NOT_FOUND",
                    event.reviewId().value(), topicId.value());
            return;
        }
        if (!isDebateStage(review.stage())) {
            LOGGER.warn("CHALLENGE_QUEUE_SKIPPED reviewId={} topicId={} reason=STAGE_NOT_DEBATE stage={}",
                    event.reviewId().value(), topicId.value(), review.stage());
            return;
        }
        DebateTopic topic = debateStore.findTopic(review.id(), topicId).orElse(null);
        if (topic == null) {
            LOGGER.warn("CHALLENGE_QUEUE_SKIPPED reviewId={} topicId={} reason=TOPIC_NOT_FOUND",
                    event.reviewId().value(), topicId.value());
            return;
        }
        if (topic.status().isTerminal()) {
            LOGGER.warn("CHALLENGE_QUEUE_SKIPPED reviewId={} topicId={} reason=TOPIC_TERMINAL status={}",
                    event.reviewId().value(), topicId.value(), topic.status());
            return;
        }
        // [AIREVIEW-PLAN-059#4] 串行闸：仅焦点议题签发质询信封；非焦点议题排队等待焦点前进。
        if (serialEnabled()) {
            Optional<DebateTopic> focus = DebateFocusResolver.focus(debateStore, review.id());
            if (focus.isPresent() && !focus.get().id().equals(topic.id())) {
                LOGGER.info("CHALLENGE_QUEUE_SKIPPED reviewId={} topicId={} reason=SERIAL_NOT_FOCUS focusTopicId={}",
                        review.id().value(), topicId.value(), focus.get().id().value());
                return;
            }
        }
        // [AIREVIEW-PLAN-047#1] The envelope round follows the topic's own round: a topic whose
        // second round began gets round-two challenge envelopes, everything else round one.
        int round = topic.currentRound() == 2 ? 2 : 1;
        advanceChallengeQueue(review, topic, round, reason);
    }

    /**
     * [AIREVIEW-PLAN-076#1] 队列串行核心：按挂载序选择第一个满足“本轮尚无 CHALLENGE turn 且无 PENDING
     * CHALLENGE 信封”的 OPPOSE 角色，为其签发一条 CHALLENGE 后立即返回；无满足者则不签发，
     * 等待下一个触发点（REBUTTAL/信封消费/过期/拒绝/第二轮开始）或 liveness 确定性收口。
     */
    private void advanceChallengeQueue(Review review, DebateTopic topic, int round, String reason) {
        Claim target = highestSeveritySupportClaim(review.id(), topic);
        if (target == null) {
            LOGGER.info("CHALLENGE_QUEUE_SKIPPED reviewId={} topicId={} reason=NO_SUPPORT_CLAIM",
                    review.id().value(), topic.id().value());
            return;
        }
        List<RoleType> queue = topic.claimIds().stream()
                .map(claimId -> debateStore.findClaim(review.id(), claimId).orElse(null))
                .filter(claim -> claim != null && claim.position() == ClaimPosition.OPPOSE
                        && claim.status() != ClaimStatus.WITHDRAWN)
                .map(Claim::roleType)
                .distinct()
                .filter(role -> role != target.roleType())
                .toList();
        for (RoleType recipient : queue) {
            boolean challengedThisRound = debateStore.findTurns(review.id(), topic.id()).stream()
                    .anyMatch(turn -> turn.turnType() == DebateTurnType.CHALLENGE
                            && turn.round() == round && turn.actorRole() == recipient);
            if (challengedThisRound) {
                continue;
            }
            boolean pendingChallenge = dispatchService.pendingFor(review.id(), review.attemptNo(), recipient)
                    .stream()
                    .anyMatch(command -> command.topicId() != null
                            && command.topicId().equals(topic.id())
                            && command.allowedAction() == DispatchedAction.CHALLENGE);
            if (pendingChallenge) {
                continue;
            }
            try {
                synchronized (review) {
                    dispatchService.issue(review, new ReviewDispatchService.DispatchProposal(
                            new ReviewCommandMetadata(review.id(), review.version(),
                                    new IdempotencyKey("dispatch:challenge:" + topic.id().value() + ":" + recipient)),
                            recipient,
                            DispatchedAction.CHALLENGE,
                            round,
                            topic.id(),
                            target.claimId(),
                            null,
                            Instant.now().plus(CHALLENGE_DISPATCH_TTL),
                            RoleType.DIRECTOR,
                            reason));
                }
            } catch (RuntimeException exception) {
                // 单个角色签发失败不阻断本角色之后的队列推进机会；日志点名并停在当前触发点。
                LOGGER.warn("CHALLENGE_QUEUE_ISSUE_FAILED reviewId={} topicId={} recipient={} targetClaimId={}",
                        review.id().value(), topic.id().value(), recipient, target.claimId().value(), exception);
            }
            return;
        }
        LOGGER.info("CHALLENGE_QUEUE_SKIPPED reviewId={} topicId={} reason=NO_ELIGIBLE_OPPOSE_ROLE round={}",
                review.id().value(), topic.id().value(), round);
    }

    /**
     * [AIREVIEW-PLAN-046#1] Highest-severity SUPPORT claim of the topic (P0 most severe); iterating
     * the topic's claimIds in mount order keeps the first occurrence when severities tie.
     */
    private Claim highestSeveritySupportClaim(ReviewId reviewId, DebateTopic topic) {
        Claim best = null;
        for (ClaimId claimId : topic.claimIds()) {
            Claim candidate = debateStore.findClaim(reviewId, claimId).orElse(null);
            if (candidate == null || candidate.position() != ClaimPosition.SUPPORT
                    || candidate.status() == ClaimStatus.WITHDRAWN) {
                continue;
            }
            if (best == null || candidate.severity().ordinal() < best.severity().ordinal()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * [AIREVIEW-PLAN-047#1] The single DEBATE phase plus the legacy round stages are all "debate"
     * for challenge/rebuttal dispatch and stage-gating; per-topic rounds come from
     * {@code DebateTopic.currentRound()}.
     */
    private static boolean isDebateStage(ReviewStage stage) {
        return stage == ReviewStage.DEBATE
                || stage == ReviewStage.DEBATE_ROUND_1
                || stage == ReviewStage.DEBATE_ROUND_2;
    }

    /** Injects the persisted envelope into exactly the recipient role's context. */
    private void deliverDispatchEnvelope(ReviewEvent event) {
        if (dispatchService == null) {
            return;
        }
        String commandIdText = event.payload().get("commandId");
        if (commandIdText == null || commandIdText.isBlank()) {
            LOGGER.warn("REVIEW_DISPATCH_ENVELOPE_MISSING_COMMAND_ID reviewId={}", event.reviewId().value());
            return;
        }
        ReviewDispatchCommand command = dispatchService
                .find(event.reviewId(), new ReviewDispatchCommand.CommandId(UUID.fromString(commandIdText)))
                .orElse(null);
        if (command == null) {
            LOGGER.warn("REVIEW_DISPATCH_ENVELOPE_COMMAND_NOT_FOUND reviewId={} commandId={}",
                    event.reviewId().value(), commandIdText);
            return;
        }
        if (command.status() != ReviewDispatchCommand.DispatchCommandStatus.PENDING) {
            LOGGER.info("REVIEW_DISPATCH_ENVELOPE_SKIPPED commandId={} status={}",
                    command.commandId().value(), command.status());
            return;
        }
        if (command.isExpiredAt(Instant.now())) {
            LOGGER.info("REVIEW_DISPATCH_ENVELOPE_EXPIRED_BEFORE_DELIVERY commandId={}", command.commandId().value());
            return;
        }
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(command.reviewId(), command.attemptNo());
        String recipient = roleLabel(runtimeId, command.recipientRole());
        LOGGER.info("REVIEW_DISPATCH_ENVELOPE_DELIVERING runtimeId={} recipient={} commandId={} action={}",
                runtimeId, recipient, command.commandId().value(), command.allowedAction());
        reactor.core.publisher.Sinks.EmitResult result = queue(runtimeId)
                .tryEmitNext(new Dispatch(recipient, ReviewDispatchService.envelopeText(command), command));
        if (result.isFailure()) {
            // A dropped dispatch would silently miss a directed wake and stall the debate.
            LOGGER.warn("REVIEW_WORKFLOW_DISPATCH_DROPPED runtimeId={} recipient={} commandId={} result={}",
                    runtimeId, recipient, command.commandId().value(), result);
        }
    }

    private void rejectPendingCommands(ReviewEvent event, String reason) {
        if (dispatchService == null || reviewRegistry == null) {
            return;
        }
        reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .ifPresent(review -> {
                    try {
                        synchronized (review) {
                            dispatchService.rejectAllPending(review, reason);
                        }
                    } catch (RuntimeException exception) {
                        LOGGER.warn("REVIEW_DISPATCH_REJECT_PENDING_FAILED reviewId={} reason={}",
                                event.reviewId().value(), reason, exception);
                    }
                });
    }

    private void send(String runtimeId, String recipient, String message) {
        reactor.core.publisher.Sinks.EmitResult result = queue(runtimeId).tryEmitNext(new Dispatch(recipient, message, null));
        if (result.isFailure()) {
            // A dropped dispatch would silently miss a Director wake and stall the review.
            LOGGER.warn("REVIEW_WORKFLOW_DISPATCH_DROPPED runtimeId={} recipient={} result={}",
                    runtimeId, recipient, result);
        }
    }

    /**
     * [AIREVIEW-PLAN-024#方案4 收口] Every Director wake during the debate rounds is counted by the
     * convergence guard so a looping Director is deterministically force-converged server-side.
     */
    private void wakeDirector(ReviewEvent event, String message) {
        // [AIREVIEW-PLAN-075#3] 仅单一 DEBATE 阶段启用冷却：同一文案在 60 秒内重复唤醒且期间无新回合
        // 事件时直接丢弃；有进展或文案变化会立即放行，不影响 JUDGING_STARTED 等关键唤醒。
        if (isSingleDebateReview(event)) {
            String key = cooldownKey(event);
            WakeCooldownState cooldown = wakeCooldowns.computeIfAbsent(key, ignored -> new WakeCooldownState());
            Instant now = Instant.now();
            if (Duration.between(cooldown.lastWakeAt, now).compareTo(COORDINATOR_WAKE_COOLDOWN) < 0
                    && cooldown.lastWakeHash == message.hashCode()
                    && !cooldown.lastTurnEventAt.isAfter(cooldown.lastWakeAt)) {
                LOGGER.info("COORDINATOR_WAKE_COOLDOWN reviewId={} attemptNo={} lastWakeAt={} lastTurnEventAt={} messageHash={}",
                        event.reviewId().value(), event.attemptNo(), cooldown.lastWakeAt,
                        cooldown.lastTurnEventAt, cooldown.lastWakeHash);
                return;
            }
            cooldown.lastWakeAt = now;
            cooldown.lastWakeHash = message.hashCode();
        }
        DebateConvergenceGuard guard = convergenceGuardProvider == null
                ? null : convergenceGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.noteDirectorWake(event.reviewId(), event.attemptNo());
        }
        send(runtimeId(event), directorLabel(event), message);
    }

    private void clearGuard(ReviewEvent event) {
        DebateConvergenceGuard guard = convergenceGuardProvider == null
                ? null : convergenceGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.clear(event.reviewId(), event.attemptNo());
        }
    }

    /**
     * [AIREVIEW-PLAN-075#3] 仅四个“真回合”事实刷新 lastTurnEventAt：新回合会立即解除同一文案的
     * 重复 wakeDirector 冷却。CLAIM_SUBMITTED 也计入，因为在 DEBATE 阶段提交主张就是辩论进展。
     */
    private void recordTurnActivity(ReviewEvent event) {
        ReviewEventType type = event.type();
        if (type != ReviewEventType.CHALLENGE_SUBMITTED
                && type != ReviewEventType.REBUTTAL_SUBMITTED
                && type != ReviewEventType.CLAIM_SUBMITTED
                && type != ReviewEventType.POSITION_CHANGED) {
            return;
        }
        wakeCooldowns.computeIfAbsent(cooldownKey(event), ignored -> new WakeCooldownState())
                .lastTurnEventAt = Instant.now();
    }

    /** [AIREVIEW-PLAN-075#3] 冷却只在评审当前处于单一 DEBATE 阶段时生效；旧轮次阶段与裁决阶段不走。 */
    private boolean isSingleDebateReview(ReviewEvent event) {
        if (reviewRegistry == null) {
            return false;
        }
        Review current = reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .orElse(null);
        return current != null && current.stage() == ReviewStage.DEBATE;
    }

    private static String cooldownKey(ReviewEvent event) {
        return event.reviewId().value() + ":" + event.attemptNo();
    }

    /**
     * [AIREVIEW-PLAN-069#4] 统一终态清理：complete+remove 本 attempt 的调度队列、清空辩论收敛守卫，
     * 并经懒 ObjectProvider 解析依次清 liveness 状态、plan 推广水印、runtime trace 与编排三张观测表，
     * 最后尽力关闭运行体。每一步独立容错，任何一步失败都不阻断其余清理，且整体幂等。
     */
    private void cleanupTerminalAttempt(ReviewEvent event, String reason) {
        String runtimeId = runtimeId(event);
        reactor.core.publisher.Sinks.Many<Dispatch> queue = queues.remove(runtimeId);
        if (queue != null) {
            queue.tryEmitComplete();
        }
        // [AIREVIEW-PLAN-075#3] 终态清理同时移除 wake 冷却状态，避免成功评审 per-attempt 冷却永久驻留。
        wakeCooldowns.remove(cooldownKey(event));
        clearGuard(event);
        if (livenessGuardProvider != null) {
            ReviewLivenessGuard livenessGuard = livenessGuardProvider.getIfAvailable();
            if (livenessGuard != null) {
                livenessGuard.clear(event.reviewId(), event.attemptNo());
            }
        }
        if (planPromoterProvider != null) {
            DirectorPlanRevisionPromoter promoter = planPromoterProvider.getIfAvailable();
            if (promoter != null) {
                promoter.clear(runtimeId);
            }
        }
        if (traceRegistryProvider != null) {
            ReviewRuntimeTraceRegistry traceRegistry = traceRegistryProvider.getIfAvailable();
            if (traceRegistry != null) {
                traceRegistry.remove(runtimeId);
            }
        }
        if (orchestrationProvider != null) {
            ReviewOrchestrationService orchestration = orchestrationProvider.getIfAvailable();
            if (orchestration != null) {
                orchestration.forget(runtimeId);
                orchestration.releaseRuntime(event.reviewId(), event.attemptNo())
                        .subscribe(null, failure -> LOGGER.warn(
                                "REVIEW_TERMINAL_RUNTIME_RELEASE_FAILED runtimeId={} reason={} error={}",
                                runtimeId, reason, failure.toString()));
            }
        }
        LOGGER.info("REVIEW_TERMINAL_CLEANUP reviewId={} attemptNo={} reason={} runtimeId={}",
                event.reviewId().value(), event.attemptNo(), reason, runtimeId);
    }

    private reactor.core.publisher.Sinks.Many<Dispatch> queue(String runtimeId) {
        return queues.computeIfAbsent(runtimeId, ignored -> {
            reactor.core.publisher.Sinks.Many<Dispatch> queue = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
            queue.asFlux()
                    .concatMap(dispatch -> deliver(runtimeId, dispatch)
                            .onErrorResume(exception -> {
                                LOGGER.warn("REVIEW_WORKFLOW_DISPATCH_FAILED runtimeId={} recipient={} commandId={}",
                                        runtimeId, dispatch.recipient(),
                                        dispatch.command() == null ? "-" : dispatch.command().commandId().value(),
                                        exception);
                                return reactor.core.publisher.Mono.empty();
                            }))
                    .subscribe();
            return queue;
        });
    }

    private reactor.core.publisher.Mono<Void> deliver(String runtimeId, Dispatch dispatch) {
        AgentRuntimeAdapter adapter = runtimeAdapterProvider.getIfAvailable();
        if (adapter == null) {
            return reactor.core.publisher.Mono.empty();
        }
        if (dispatch.command() != null) {
            return adapter.deliverDispatchCommand(runtimeId, dispatch.recipient(), dispatch.message(), dispatch.command());
        }
        return adapter.send(runtimeId, dispatch.recipient(), dispatch.message());
    }

    private String directorLabel(ReviewEvent event) {
        return runtimeId(event) + "-director";
    }

    private String runtimeId(ReviewEvent event) {
        return ReviewRuntimeContext.runtimeIdFor(event.reviewId(), event.attemptNo());
    }

    private String roleLabel(String runtimeId, RoleType roleType) {
        return runtimeId + "-" + roleType.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** [AIREVIEW-PLAN-075#3] per-attempt wake 冷却状态；默认 epoch 保证首次唤醒永不被抑制。 */
    private static final class WakeCooldownState {
        private volatile Instant lastTurnEventAt = Instant.EPOCH;
        private volatile Instant lastWakeAt = Instant.EPOCH;
        private volatile int lastWakeHash;
    }

    /** @author wangli */
    private static final class Dispatch {
        private final String recipient;
        private final String message;
        private final ReviewDispatchCommand command;

        private Dispatch(String recipient, String message, ReviewDispatchCommand command) {
            this.recipient = Objects.requireNonNull(recipient, "recipient must not be null");
            this.message = Objects.requireNonNull(message, "message must not be null");
            this.command = command;
        }

        private String recipient() {
            return recipient;
        }

        private String message() {
            return message;
        }

        private ReviewDispatchCommand command() {
            return command;
        }
    }
}
