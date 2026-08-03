package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.application.ReviewEventListener;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H2] Mirrors each committed runtime fact back to the durable review root.
 *
 * <p>The root remains the platform-list source of truth, while event history remains append-only. The attempt/version
 * predicate prevents a late event from an older attempt from moving the root backward.
 *
 * @author zyj
 */
@Component
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewStateProjectionListener implements ReviewEventListener {

    private final ReviewRegistry reviewRegistry;
    private final ReviewPersistenceMapper mapper;

    public MyBatisReviewStateProjectionListener(ReviewRegistry reviewRegistry, ReviewPersistenceMapper mapper) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public void onCommitted(ReviewEvent event) {
        reviewRegistry.find(event.reviewId())
                .filter(review -> review.attemptNo() == event.attemptNo())
                .ifPresent(review -> mapper.synchronizeReviewRoot(
                        review.id().value().toString(),
                        review.stage().name(),
                        review.attemptNo(),
                        review.version()));
    }
}
