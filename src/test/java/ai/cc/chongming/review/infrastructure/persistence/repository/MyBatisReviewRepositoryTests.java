package ai.cc.chongming.review.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * Tests batch mapping and aggregate rehydration in the MyBatis repository.
 *
 * @author wangli
 */
class MyBatisReviewRepositoryTests {

    private final ReviewPersistenceMapper mapper = mock(ReviewPersistenceMapper.class);
    private final MyBatisReviewRepository repository = new MyBatisReviewRepository(mapper);

    @Test
    void restoresReviewWithRolesAndIdempotencyResults() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        String persistedId = reviewId.value().toString();
        when(mapper.findReview(persistedId)).thenReturn(
                new ReviewPersistenceMapper.ReviewRow(persistedId, "INITIAL_REVIEW", 2, 7));
        when(mapper.findRoleActivations(persistedId)).thenReturn(List.of(
                new ReviewPersistenceMapper.RoleActivationRow(
                        UUID.randomUUID().toString(), persistedId, 2, "BACKEND", "backend-1", "ACTIVATED")));
        when(mapper.findCommandResults(persistedId)).thenReturn(List.of(
                new ReviewPersistenceMapper.CommandResultRow(persistedId, "command-1", "event-1")));

        var review = repository.findReview(reviewId).orElseThrow();

        assertThat(review.stage()).isEqualTo(ReviewStage.INITIAL_REVIEW);
        assertThat(review.attemptNo()).isEqualTo(2);
        assertThat(review.version()).isEqualTo(7);
        assertThat(review.roleActivations()).containsExactly(new RoleActivation(RoleType.BACKEND, "backend-1", false));
        assertThat(review.commandResults()).containsEntry(new IdempotencyKey("command-1"), "event-1");
    }

    @Test
    void batchLoadsClaimsAndTheirEvidenceWithoutPerClaimQueries() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ClaimId claimId = new ClaimId(UUID.randomUUID());
        EvidenceId evidenceId = new EvidenceId(UUID.randomUUID());
        String persistedReviewId = reviewId.value().toString();
        String persistedClaimId = claimId.value().toString();
        Set<String> persistedIds = Set.of(persistedClaimId);

        when(mapper.findEvidenceByClaimIds(persistedIds)).thenReturn(List.of(
                new ReviewPersistenceMapper.ClaimEvidenceRow(
                        persistedClaimId,
                        evidenceId.value().toString(),
                        "snapshot-1",
                        "src/main/java/App.java",
                        12,
                        "a".repeat(64))));
        when(mapper.findClaimsByIds(persistedReviewId, persistedIds)).thenReturn(List.of(
                new ReviewPersistenceMapper.ClaimRow(
                        persistedClaimId,
                        persistedReviewId,
                        "BACKEND",
                        "api",
                        "P1",
                        "OPPOSE",
                        "The API contract is incomplete.",
                        "A required field is missing.",
                        "SUBMITTED")));

        Claim claim = repository.findClaimsByIds(reviewId, Set.of(claimId)).get(claimId);

        assertThat(claim.evidenceReferences()).containsExactly(new EvidenceReference(
                evidenceId, "snapshot-1", "src/main/java/App.java", 12, "a".repeat(64)));
        verify(mapper).findEvidenceByClaimIds(persistedIds);
        verify(mapper).findClaimsByIds(persistedReviewId, persistedIds);
    }
}
