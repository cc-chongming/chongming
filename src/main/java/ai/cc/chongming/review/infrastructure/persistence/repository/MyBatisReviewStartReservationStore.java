package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewStartReservationStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#2][REQLIFE-H1] Compares and swaps the persisted root before a runtime may start.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewStartReservationStore implements ReviewStartReservationStore {

    private final ReviewPersistenceMapper mapper;

    public MyBatisReviewStartReservationStore(ReviewPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public boolean claimStartFromPending(ReviewId reviewId, long expectedVersion, int attemptNo, long nextVersion) {
        return mapper.claimStartFromPending(
                reviewId.value().toString(), expectedVersion, attemptNo, nextVersion) == 1;
    }
}
