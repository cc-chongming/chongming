package ai.cc.chongming.review.domain;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionActor;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-003#1.5,#1.6] Verifies protocol limits, safe Gate boundaries and command consistency.
 *
 * @author wangli
 */
class ReviewProtocolGuardTests {

    private final ReviewProtocolGuard guard = new ReviewProtocolGuard();

    @Test
    void requiresAllCoreRolesBeforeDebateCanBegin() {
        List<RoleActivation> activatedRoles = List.of(
                activation(RoleType.PRODUCT),
                activation(RoleType.PROJECT),
                activation(RoleType.FRONTEND));

        assertThat(guard.validateDebateStart(activatedRoles).errorCode())
                .isEqualTo(ReviewErrorCode.CORE_ROLE_INITIAL_REVIEW_REQUIRED);
    }

    @Test
    void rejectsNinthAgentActivation() {
        List<RoleActivation> activatedRoles = List.of(
                activation(RoleType.PRODUCT),
                activation(RoleType.PROJECT),
                activation(RoleType.FRONTEND),
                activation(RoleType.BACKEND),
                activation(RoleType.SECURITY),
                activation(RoleType.ARCHITECTURE),
                activation(RoleType.TESTING),
                activation(RoleType.JUDGE));

        assertThat(guard.validateRoleActivation(activatedRoles, RoleType.PERFORMANCE).errorCode())
                .isEqualTo(ReviewErrorCode.AGENT_LIMIT_EXCEEDED);
    }

    @Test
    void marksHighSeverityClaimWithoutEvidenceAsUnverifiedAndPreventsAutoBlock() {
        Claim claim = new Claim(
                new ClaimId(UUID.randomUUID()),
                new ReviewId(UUID.randomUUID()),
                RoleType.BACKEND,
                "batch-import",
                ClaimSeverity.P1,
                ClaimPosition.OPPOSE,
                "The change lacks a transaction boundary.",
                "The data consistency risk needs confirmation.",
                List.of());

        Claim normalized = guard.normalizeClaim(claim);

        assertThat(normalized.status()).isEqualTo(ClaimStatus.UNVERIFIED);
        assertThat(guard.normalizeAiGateDraft(GateResult.BLOCK, List.of(normalized)))
                .isEqualTo(GateResult.HUMAN_REQUIRED);
    }

    @Test
    void rejectsAiFinalGateButAllowsAiDraft() {
        assertThat(guard.validateGateDecision(DecisionActor.AI, DecisionStatus.DRAFT).isValid()).isTrue();

        assertThat(guard.validateGateDecision(DecisionActor.AI, DecisionStatus.FINAL).errorCode())
                .isEqualTo(ReviewErrorCode.FINAL_GATE_REQUIRES_HUMAN);
        assertThat(guard.validateGateDecision(DecisionActor.HUMAN, DecisionStatus.FINAL).isValid()).isTrue();
    }

    @Test
    void replaysTheFirstCommandResultForTheSameIdempotencyKey() {
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        TopicId topicId = new TopicId(UUID.randomUUID());
        IdempotencyKey key = IdempotencyKey.of(review.id(), topicId, 1, RoleType.BACKEND, DebateTurnType.CHALLENGE);
        ReviewCommandMetadata metadata = new ReviewCommandMetadata(review.id(), 0L, key);

        String first = review.recordCommand(metadata, "turn-001");
        String replayed = review.recordCommand(metadata, "turn-002");

        assertThat(replayed).isEqualTo(first);
        assertThat(review.version()).isEqualTo(1L);
    }

    @Test
    void rejectsCommandsWithAnOutdatedExpectedVersion() {
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        ReviewCommandMetadata metadata = new ReviewCommandMetadata(
                review.id(),
                1L,
                IdempotencyKey.of(review.id(), null, 0, RoleType.PRODUCT, DebateTurnType.CHALLENGE));

        assertThatThrownBy(() -> review.recordCommand(metadata, "claim-001"))
                .isInstanceOf(ReviewDomainException.class)
                .extracting(error -> ((ReviewDomainException) error).errorCode())
                .isEqualTo(ReviewErrorCode.VERSION_CONFLICT);
    }

    private RoleActivation activation(RoleType roleType) {
        return new RoleActivation(roleType, roleType.name().toLowerCase(), true);
    }
}

