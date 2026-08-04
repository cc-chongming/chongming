package ai.cc.chongming.review.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-021#2][REQLIFE-H1] Verifies durable binding relies on the persisted root stage.
 *
 * @author zyj
 */
class MyBatisReviewRequirementLinkStoreTests {

    @Test
    void rejectsAReviewWhosePersistedRootHasAlreadyStarted() {
        ReviewPersistenceMapper mapper = mock(ReviewPersistenceMapper.class);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        RequirementId requirementId = new RequirementId(UUID.randomUUID());
        when(mapper.lockReviewRequirementBinding(reviewId.value().toString())).thenReturn(
                new ReviewPersistenceMapper.ReviewRequirementBindingRow(
                        reviewId.value().toString(), "PLANNING", null));

        boolean bound = new MyBatisReviewRequirementLinkStore(mapper)
                .tryBindPendingReview(reviewId, requirementId);

        assertThat(bound).isFalse();
        verify(mapper, never()).linkPendingUnboundReviewToRequirement(
                requirementId.value().toString(), reviewId.value().toString());
    }

    @Test
    void clearsThePersistedReverseLinkWhenTheRequirementIsDeleted() {
        ReviewPersistenceMapper mapper = mock(ReviewPersistenceMapper.class);
        RequirementId requirementId = new RequirementId(UUID.randomUUID());

        new MyBatisReviewRequirementLinkStore(mapper).unlinkRequirement(requirementId);

        verify(mapper).unlinkRequirement(requirementId.value().toString());
    }
}
