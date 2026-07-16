package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.domain.gate.GatePolicy;
import ai.cc.chongming.review.domain.model.Claim;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies safety precedence for AI Gate drafts.
 *
 * @author wangli
 */
class GatePolicyTests {

    @Test
    void requiresHumanWhenHighSeverityClaimHasNoEvidence() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Claim unverified = new Claim(new ClaimId(UUID.randomUUID()), reviewId, RoleType.SECURITY, "authentication",
                ClaimSeverity.P0, ClaimPosition.OPPOSE, "Missing auth policy", "No evidence", List.of()).withStatus(ClaimStatus.UNVERIFIED);

        assertThat(new GatePolicy().draft(reviewId, List.of(unverified), List.of()).result())
                .isEqualTo(GateResult.HUMAN_REQUIRED);
    }

    @Test
    void usesConfiguredNonFinalResultForVerifiedP1Opposition() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Claim opposing = new Claim(new ClaimId(UUID.randomUUID()), reviewId, RoleType.BACKEND, "authentication",
                ClaimSeverity.P1, ClaimPosition.OPPOSE, "Missing expiry policy", "Policy is incomplete", List.of());

        assertThat(new GatePolicy(GateResult.RETURN).draft(reviewId, List.of(opposing), List.of()).result())
                .isEqualTo(GateResult.RETURN);
    }
}
