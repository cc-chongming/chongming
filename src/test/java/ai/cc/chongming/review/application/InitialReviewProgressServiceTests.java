package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies first-review completion advances only after all mandatory roles have explicitly finished.
 *
 * @author wangli
 */
class InitialReviewProgressServiceTests {

    @Test
    void advancesToConflictDetectionOnlyAfterFourCoreRolesComplete() {
        Review review = Review.restore(new ReviewId(UUID.randomUUID()), ReviewStage.INITIAL_REVIEW, 1, 0,
                List.of(
                        new RoleActivation(RoleType.PRODUCT, "product", false),
                        new RoleActivation(RoleType.PROJECT, "project", false),
                        new RoleActivation(RoleType.FRONTEND, "frontend", false),
                        new RoleActivation(RoleType.BACKEND, "backend", false)),
                java.util.Map.of());
        List<ReviewEventDraft> events = new ArrayList<>();
        InitialReviewProgressService service = new InitialReviewProgressService(
                new ReviewProtocolGuard(), new ReviewStateMachine(), events::add);

        complete(service, review, RoleType.PRODUCT, "call-1");
        complete(service, review, RoleType.PROJECT, "call-2");
        complete(service, review, RoleType.FRONTEND, "call-3");

        assertThat(review.stage()).isEqualTo(ReviewStage.INITIAL_REVIEW);

        complete(service, review, RoleType.BACKEND, "call-4");

        assertThat(review.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
        assertThat(events).extracting(ReviewEventDraft::type)
                .containsExactly(ReviewEventType.ROLE_COMPLETED, ReviewEventType.ROLE_COMPLETED,
                        ReviewEventType.ROLE_COMPLETED, ReviewEventType.ROLE_COMPLETED,
                        ReviewEventType.INITIAL_REVIEW_COMPLETED);

        InitialReviewProgressService.CompletionResult replay = service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("call-4")),
                RoleType.BACKEND, "same completion");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stage()).isEqualTo(ReviewStage.CONFLICT_DETECTION);
    }

    private void complete(InitialReviewProgressService service, Review review, RoleType roleType, String key) {
        service.completeWithoutClaim(review,
                new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key)), roleType,
                "No blocking finding for " + roleType);
    }
}
