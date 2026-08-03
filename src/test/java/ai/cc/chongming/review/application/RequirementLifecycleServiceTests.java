package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-021#3] Verifies review events advance the linked requirement in one direction.
 *
 * @author zyj
 */
class RequirementLifecycleServiceTests {

    @Test
    void movesLinkedRequirementToReviewingWhenAPlanIsCreated() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "身份同步", "同步资料", "alice", null, "cx-ai", "P1");
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        repository.save(requirement);
        RequirementCommandService commandService = new RequirementCommandService(
                repository, () -> new ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity(
                        "alice", java.util.Set.of()));
        commandService.submitForReview(requirement.id(), reviewId, requirement.version());
        RequirementLifecycleService service = new RequirementLifecycleService(commandService, repository);

        service.onCommitted(event(reviewId, ReviewEventType.PLAN_CREATED, Map.of()));

        assertThat(repository.findById(requirement.id()).orElseThrow().status()).isEqualTo(RequirementStatus.REVIEWING);
    }

    @Test
    void appliesFinalHumanGateDecisionToLinkedRequirement() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "身份同步", "同步资料", "alice", null, "cx-ai", "P1");
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        repository.save(requirement);
        RequirementCommandService commandService = new RequirementCommandService(
                repository, () -> new ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity(
                        "alice", java.util.Set.of()));
        commandService.submitForReview(requirement.id(), reviewId, requirement.version());
        commandService.markReviewStarted(reviewId);
        RequirementLifecycleService service = new RequirementLifecycleService(commandService, repository);

        service.onCommitted(event(reviewId, ReviewEventType.HUMAN_GATE_FINALIZED, Map.of("result", "PASS")));

        assertThat(repository.findById(requirement.id()).orElseThrow().status()).isEqualTo(RequirementStatus.APPROVED);
    }

    private ReviewEvent event(ReviewId reviewId, ReviewEventType type, Map<String, String> payload) {
        return ReviewEvent.committed(1L, new ReviewEventDraft(
                reviewId,
                1,
                type,
                ReviewStage.PLANNING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                1,
                payload));
    }
}
