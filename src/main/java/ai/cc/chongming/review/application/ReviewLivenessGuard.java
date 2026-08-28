package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final ConcurrentMap<String, LivenessState> states = new ConcurrentHashMap<>();

    @Autowired
    public ReviewLivenessGuard(
            ReviewRegistry reviewRegistry,
            AgentScopeProperties properties,
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ObjectProvider<ConflictDetectionService> conflictDetectionServiceProvider,
            ObjectProvider<DebateService> debateServiceProvider,
            ObjectProvider<JudgeService> judgeServiceProvider,
            ObjectProvider<ReviewCommandService> reviewCommandServiceProvider) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.runtimeAdapterProvider = Objects.requireNonNull(runtimeAdapterProvider, "runtimeAdapterProvider must not be null");
        this.conflictDetectionServiceProvider = conflictDetectionServiceProvider;
        this.debateServiceProvider = debateServiceProvider;
        this.judgeServiceProvider = judgeServiceProvider;
        this.reviewCommandServiceProvider = reviewCommandServiceProvider;
    }

    /** [AIREVIEW-PLAN-060#1] committed events are the heartbeat feed; terminal events forget the attempt. */
    @Override
    public void onCommitted(ReviewEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String key = key(event.reviewId(), event.attemptNo());
        if (event.type() == ReviewEventType.REVIEW_CANCELLED || event.type() == ReviewEventType.REVIEW_FAILED) {
            if (states.remove(key) != null) {
                LOGGER.info("LIVENESS_STATE_CLEARED reviewId={} attemptNo={} eventType={}",
                        event.reviewId().value(), event.attemptNo(), event.type());
            }
            return;
        }
        LivenessState state = states.computeIfAbsent(key, ignored -> new LivenessState());
        state.lastActivityAt = Instant.now();
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
        Instant now = Instant.now();
        for (Map.Entry<String, LivenessState> entry : states.entrySet()) {
            String key = entry.getKey();
            LivenessState state = entry.getValue();
            try {
                if (Duration.between(state.lastActivityAt, now)
                        .compareTo(properties.livenessRewakeIdle()) < 0) {
                    continue;
                }
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
                ReviewStage stage = state.stage;
                if (!isCoveredStage(stage)) {
                    continue;
                }
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
            send(adapter, runtimeId, roleLabel(runtimeId, activation.roleType()),
                    "初审仍未完成，请尽快提交 Claim 并调用 complete_initial_review");
            sent = true;
        }
        return sent;
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
        boolean failed;
        synchronized (review) {
            failed = commandService.failReview(review,
                    "LIVENESS_TIMEOUT: 初审活性超时，未完成角色=[" + incompleteRoles + "]");
        }
        if (failed) {
            LOGGER.warn("LIVENESS_FORCE_FAIL reviewId={} attemptNo={} incompleteRoles={}",
                    review.id().value(), review.attemptNo(), incompleteRoles);
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
        private volatile ReviewStage stage;
        private final ConcurrentMap<ReviewStage, AtomicInteger> rewakes = new ConcurrentHashMap<>();
    }
}
