package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.AgentScopeProperties;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class DebateConvergenceGuard {

    private static final Logger log = LoggerFactory.getLogger(DebateConvergenceGuard.class);

    private final ReviewRegistry reviewRegistry;
    private final DebateService debateService;
    private final ReviewDebateStore debateStore;
    private final ReviewDispatchStore dispatchStore;
    private final AgentScopeProperties properties;
    private final ConcurrentMap<String, WakeState> states = new ConcurrentHashMap<>();

    public DebateConvergenceGuard(
            ReviewRegistry reviewRegistry,
            DebateService debateService,
            ReviewDebateStore debateStore,
            AgentScopeProperties properties) {
        this(reviewRegistry, debateService, debateStore, null, properties);
    }

    @Autowired
    public DebateConvergenceGuard(
            ReviewRegistry reviewRegistry,
            DebateService debateService,
            ReviewDebateStore debateStore,
            ReviewDispatchStore dispatchStore,
            AgentScopeProperties properties) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.debateService = Objects.requireNonNull(debateService, "debateService must not be null");
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.dispatchStore = dispatchStore;
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
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
        // Drop the state before converging so events published by the convergence itself never
        // re-enter with a stale counter.
        states.remove(key);
        forceConvergence(reviewId, attemptNo, wakes, elapsed, "wake-or-wall-clock");
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
            Duration idle = Duration.between(state.lastWakeAt, now);
            // [AIREVIEW-PLAN-059#6] 墙钟预算按议题数缩放；no-progress 与 expired-dispatch 不缩放。
            String[] parts = entry.getKey().split(":");
            int topicScale = 1;
            if (parts.length == 2) {
                try {
                    topicScale = topicScale(new ReviewId(UUID.fromString(parts[0])));
                } catch (IllegalArgumentException ignored) {
                    //  malformed key: keep the unscaled budget; the parse below skips it anyway.
                }
            }
            String reason;
            if (hasExpiredPending(entry.getKey(), now)) {
                reason = "expired-dispatch";
            } else if (idle.compareTo(properties.debateNoProgressTimeout()) >= 0) {
                reason = "no-progress";
            } else if (elapsed.compareTo(properties.debateConvergenceTimeout().multipliedBy(topicScale)) >= 0) {
                reason = "wall-clock";
            } else {
                continue;
            }
            states.remove(entry.getKey());
            if (parts.length != 2) {
                continue;
            }
            try {
                forceConvergence(new ReviewId(UUID.fromString(parts[0])), Integer.parseInt(parts[1]),
                        state.wakes.get(), elapsed, reason);
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

    private void forceConvergence(ReviewId reviewId, int attemptNo, int wakes, Duration elapsed, String reason) {
        Review review = reviewRegistry.find(reviewId)
                .filter(candidate -> candidate.attemptNo() == attemptNo)
                .orElse(null);
        if (review == null) {
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
            log.warn("DEBATE_FORCED_CONVERGENCE reviewId={} attemptNo={} directorWakes={} elapsed={} reason={} escalatedTopics={}",
                    reviewId.value(), attemptNo, wakes, elapsed, reason, openTopics.size());
        }
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

    private static final class WakeState {
        private final Instant firstWakeAt = Instant.now();
        private final AtomicInteger wakes = new AtomicInteger();
        private volatile Instant lastWakeAt = Instant.now();
    }
}
