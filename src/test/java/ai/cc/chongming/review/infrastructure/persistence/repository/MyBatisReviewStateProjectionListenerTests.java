package ai.cc.chongming.review.infrastructure.persistence.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H2] Verifies durable root stages follow committed runtime events.
 *
 * @author zyj
 */
class MyBatisReviewStateProjectionListenerTests {

    @Test
    void synchronizesTheCurrentAggregateStageAndVersionAfterAnEvent() {
        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        ReviewPersistenceMapper mapper = mock(ReviewPersistenceMapper.class);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        registry.register(Review.restore(reviewId, ReviewStage.COMPLETED, 1, 9L, List.of(), Map.of()));

        new MyBatisReviewStateProjectionListener(registry, mapper).onCommitted(new ReviewEvent(
                UUID.randomUUID(),
                12L,
                reviewId,
                1,
                ReviewEventType.NOTIFICATION_SENT,
                ReviewEventType.NOTIFICATION_SENT.category(),
                ReviewStage.COMPLETED,
                null,
                null,
                null,
                null,
                null,
                null,
                100,
                Instant.now(),
                1,
                Map.of()));

        verify(mapper).synchronizeReviewRoot(reviewId.value().toString(), "COMPLETED", 1, 9L);
    }
}
