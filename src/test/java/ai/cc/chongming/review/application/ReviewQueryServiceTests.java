package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [AIREVIEW-PLAN-018#3.3] Verifies persisted Context Scout degradation remains visible after later events.
 *
 * @author wangli
 */
class ReviewQueryServiceTests {

    @Test
    void exposesCurrentAttemptScoutDegradationFromItsDedicatedEvent() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.PLANNING, 2, 7, List.of(), Map.of());
        ReviewEventStore eventStore = mock(ReviewEventStore.class);
        ReviewDebateStore debateStore = mock(ReviewDebateStore.class);
        HumanGateDecisionStore humanGateStore = mock(HumanGateDecisionStore.class);
        ReviewRegistry reviewRegistry = mock(ReviewRegistry.class);
        ReviewEvent latest = event(reviewId, 12, 2, ReviewEventType.PLAN_CREATED, Map.of());
        ReviewEvent degraded = event(reviewId, 3, 2, ReviewEventType.CONTEXT_SCOUT_DEGRADED, Map.of(
                "status", "DEGRADED",
                "reasonCode", "MODEL_CALL_TIMEOUT",
                "publicSummary", "Context Scout 模型调用超时，已跳过项目上下文预处理，Director 将继续评审。"));
        when(eventStore.findLatest(reviewId)).thenReturn(Optional.of(latest));
        when(eventStore.findLatestByTypeAndAttempt(reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED, 2))
                .thenReturn(Optional.of(degraded));
        when(debateStore.findGateDraft(reviewId)).thenReturn(Optional.empty());
        when(humanGateStore.findLatest(reviewId)).thenReturn(Optional.empty());
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(review));
        ReviewQueryService service = new ReviewQueryService(
                eventStore,
                debateStore,
                mock(EvidenceLedgerService.class),
                humanGateStore,
                reviewRegistry);

        ReviewQueryService.ReviewSummary summary = service.findSummary(reviewId).orElseThrow();

        assertThat(summary.contextScout()).isNotNull();
        assertThat(summary.contextScout().status()).isEqualTo("DEGRADED");
        assertThat(summary.contextScout().reasonCode()).isEqualTo("MODEL_CALL_TIMEOUT");
        assertThat(summary.contextScout().publicSummary())
                .isEqualTo("Context Scout 模型调用超时，已跳过项目上下文预处理，Director 将继续评审。");
    }

    @Test
    void hidesScoutDegradationFromPreviousAttempt() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.PLANNING, 2, 7, List.of(), Map.of());
        ReviewEventStore eventStore = mock(ReviewEventStore.class);
        ReviewDebateStore debateStore = mock(ReviewDebateStore.class);
        HumanGateDecisionStore humanGateStore = mock(HumanGateDecisionStore.class);
        ReviewRegistry reviewRegistry = mock(ReviewRegistry.class);
        when(eventStore.findLatest(reviewId)).thenReturn(Optional.of(event(
                reviewId, 12, 2, ReviewEventType.PLAN_CREATED, Map.of())));
        when(eventStore.findLatestByTypeAndAttempt(reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED, 2))
                .thenReturn(Optional.empty());
        when(debateStore.findGateDraft(reviewId)).thenReturn(Optional.empty());
        when(humanGateStore.findLatest(reviewId)).thenReturn(Optional.empty());
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(review));
        ReviewQueryService service = new ReviewQueryService(
                eventStore,
                debateStore,
                mock(EvidenceLedgerService.class),
                humanGateStore,
                reviewRegistry);

        assertThat(service.findSummary(reviewId).orElseThrow().contextScout()).isNull();
    }

    private ReviewEvent event(
            ReviewId reviewId, long sequence, int attempt, ReviewEventType type, Map<String, String> payload) {
        return ReviewEvent.committed(sequence, new ReviewEventDraft(
                reviewId,
                attempt,
                type,
                ReviewStage.PLANNING,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-07-29T10:00:00Z"),
                1,
                payload));
    }
}
