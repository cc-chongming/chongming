package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;

import java.util.Map;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-010#1.1][AIREVIEW-PLAN-010#1.5] Creates formal events only from completed domain commands.
 *
 * @author wangli
 */
public final class ReviewEventDrafts {

    private ReviewEventDrafts() {
    }

    public static ReviewEventDraft completedCommand(
            Review review,
            ReviewEventType type,
            RoleType actorRole,
            RoleType targetRole,
            TopicId topicId,
            ClaimId claimId,
            TurnId turnId,
            Integer round,
            Integer progress,
            Map<String, String> payload) {
        Objects.requireNonNull(review, "review must not be null");
        return new ReviewEventDraft(
                review.id(),
                review.attemptNo(),
                type,
                review.stage(),
                actorRole,
                targetRole,
                topicId,
                claimId,
                turnId,
                round,
                progress,
                null,
                1,
                payload == null ? Map.of() : payload);
    }
}
