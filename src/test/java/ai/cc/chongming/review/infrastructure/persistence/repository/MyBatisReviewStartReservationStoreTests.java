package ai.cc.chongming.review.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-021#2][REQLIFE-H1] Verifies the persistent start compare-and-swap contract.
 *
 * @author zyj
 */
class MyBatisReviewStartReservationStoreTests {

    @Test
    void claimsOnlyWhenTheMapperReportsOnePendingRootUpdated() {
        ReviewPersistenceMapper mapper = mock(ReviewPersistenceMapper.class);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        when(mapper.claimStartFromPending(reviewId.value().toString(), 2L, 3, 5L)).thenReturn(1);

        boolean claimed = new MyBatisReviewStartReservationStore(mapper)
                .claimStartFromPending(reviewId, 2L, 3, 5L);

        assertThat(claimed).isTrue();
        verify(mapper).claimStartFromPending(reviewId.value().toString(), 2L, 3, 5L);
    }
}
