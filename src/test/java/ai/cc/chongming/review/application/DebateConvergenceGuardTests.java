package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-024#方案4 收口] Verifies the server-side debate convergence fallback: a looping
 * Director is force-converged by escalating open topics and beginning judging, while bounded
 * debates stay untouched.
 *
 * @author wangli
 */
class DebateConvergenceGuardTests {

    /** [AIREVIEW-PLAN-047#1] 收敛守卫同样覆盖单一 DEBATE 阶段（议题级轮次）。 */
    @Test
    void forcesEscalationAndJudgingFromTheSingleDebateStage() {
        Fixture fixture = fixture(2, Duration.ofMinutes(20), ReviewStage.DEBATE);

        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);

        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.JUDGING);
        assertThat(fixture.store.findTopics(fixture.review.id()))
                .hasSize(2)
                .allSatisfy(topic -> assertThat(topic.status())
                        .isEqualTo(ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus.ESCALATED));
    }

    @Test
    void wakesBelowBudgetKeepTheDebateRunning() {
        Fixture fixture = fixture(3, Duration.ofMinutes(20));

        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);

        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);
        assertThat(fixture.store.findTopics(fixture.review.id()))
                .allSatisfy(topic -> assertThat(topic.status().isTerminal()).isFalse());
    }

    @Test
    void forcesEscalationAndJudgingWhenDirectorWakesExceedBudget() {
        Fixture fixture = fixture(2, Duration.ofMinutes(20));

        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);

        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.JUDGING);
        assertThat(fixture.store.findTopics(fixture.review.id()))
                .hasSize(2)
                .allSatisfy(topic -> {
                    assertThat(topic.status()).isEqualTo(ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus.ESCALATED);
                    assertThat(topic.resolution()).contains("服务端强制收敛");
                });
    }

    @Test
    void convergesOnWallClockBudgetEvenBelowTheWakeCap() {
        Fixture fixture = fixture(100, Duration.ZERO);

        fixture.guard.noteDirectorWake(fixture.review.id(), 1);

        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.JUDGING);
    }

    @Test
    void staysInactiveOnceTheReviewLeftTheDebateRounds() {
        Fixture fixture = fixture(2, Duration.ofMinutes(20));
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.JUDGING);

        // Further wakes after convergence must be no-ops, not errors or re-transitions.
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.JUDGING);
    }

    /**
     * Silent stall: no further Director wake ever arrives, so only the scheduled watchdog can
     * force convergence once the wall-clock budget elapsed.
     */
    @Test
    void watchdogConvergesStalledDebateWithoutFurtherWakes() throws InterruptedException {
        Fixture fixture = fixture(100, Duration.ofMillis(1));

        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);

        Thread.sleep(30);
        fixture.guard.scanForStalledDebates();

        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.JUDGING);
    }

    /**
     * No-progress fast path: wall-clock budget is large, but once the Director goes idle longer
     * than the (tiny) no-progress window the watchdog converges without waiting for the backstop.
     */
    @Test
    void watchdogConvergesOnNoProgressIdleBeforeTheWallClockBackstop() throws InterruptedException {
        Fixture fixture = fixture(100, Duration.ofMinutes(20), Duration.ofMillis(1));

        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);

        Thread.sleep(30);
        fixture.guard.scanForStalledDebates();

        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.JUDGING);
    }

    /** [AIREVIEW-PLAN-059#6] 串行开启时 wake 预算按议题数（夹具 2 个）缩放：2→4 次才收敛。 */
    @Test
    void serialDebateScalesTheWakeBudgetByTopicCount() {
        Fixture fixture = fixture(2, Duration.ofMinutes(20), Duration.ofMinutes(20), ReviewStage.DEBATE, true);

        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.DEBATE);

        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.JUDGING);
    }

    @Test
    void clearResetsTheWakeBudget() {
        Fixture fixture = fixture(3, Duration.ofMinutes(20));
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);

        fixture.guard.clear(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);
        fixture.guard.noteDirectorWake(fixture.review.id(), 1);

        assertThat(fixture.review.stage()).isEqualTo(ReviewStage.DEBATE_ROUND_1);
    }

    private Fixture fixture(int maxWakes, Duration timeout) {
        return fixture(maxWakes, timeout, Duration.ofMinutes(20), ReviewStage.DEBATE_ROUND_1);
    }

    private Fixture fixture(int maxWakes, Duration timeout, ReviewStage stage) {
        return fixture(maxWakes, timeout, Duration.ofMinutes(20), stage);
    }

    private Fixture fixture(int maxWakes, Duration timeout, Duration noProgress) {
        return fixture(maxWakes, timeout, noProgress, ReviewStage.DEBATE_ROUND_1);
    }

    private Fixture fixture(int maxWakes, Duration timeout, Duration noProgress, ReviewStage stage) {
        return fixture(maxWakes, timeout, noProgress, stage, false);
    }

    private Fixture fixture(int maxWakes, Duration timeout, Duration noProgress, ReviewStage stage, boolean serial) {
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), stage, 1, 0,
                List.of(), Map.of());
        registry.register(review);
        store.saveTopic(new DebateTopic(new TopicId(UUID.randomUUID()), review.id(), "cache.default_enable", List.of()));
        store.saveTopic(new DebateTopic(new TopicId(UUID.randomUUID()), review.id(), "cache.read_your_writes", List.of()));
        DebateService debateService = new DebateService(store, new EvidenceLedgerService(), new DebateStateMachine());
        AgentScopeProperties properties = new AgentScopeProperties(
                false, "state", 48, 12, 16, Duration.ofSeconds(150), maxWakes, timeout, noProgress, serial);
        return new Fixture(new DebateConvergenceGuard(registry, debateService, store, properties), store, review);
    }

    private record Fixture(DebateConvergenceGuard guard, InMemoryReviewDebateStore store, Review review) {
    }
}
