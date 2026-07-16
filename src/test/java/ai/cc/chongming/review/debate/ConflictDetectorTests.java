package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.domain.debate.ConflictDetector;
import ai.cc.chongming.review.domain.model.Claim;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies deterministic rule-based conflict recall before model ranking.
 *
 * @author wangli
 */
class ConflictDetectorTests {

    @Test
    void recallsOpposingPositionsAndSeverityMismatchForSameSubject() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ConflictDetector.ConflictDetectionResult result = new ConflictDetector().detect(List.of(
                claim(reviewId, RoleType.PRODUCT, ClaimSeverity.P2, ClaimPosition.SUPPORT),
                claim(reviewId, RoleType.BACKEND, ClaimSeverity.P0, ClaimPosition.OPPOSE)));

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.rules()).containsExactlyInAnyOrder(
                    ConflictDetector.ConflictRule.OPPOSING_POSITION,
                    ConflictDetector.ConflictRule.SEVERITY_MISMATCH);
            assertThat(candidate.score()).isGreaterThan(100);
        });
        assertThat(result.noConflicts()).isEmpty();
    }

    @Test
    void recallsOpposingClaimsThatInterpretTheSameEvidenceDifferently() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        EvidenceReference sharedEvidence = new EvidenceReference(new EvidenceId(UUID.randomUUID()), "snapshot-1",
                "src/main/java/TokenService.java", 42, "snippet-hash");
        Claim support = new Claim(new ClaimId(UUID.randomUUID()), reviewId, RoleType.PRODUCT, "authentication",
                ClaimSeverity.P1, ClaimPosition.SUPPORT, "Keep policy", "Product accepts the behavior", List.of(sharedEvidence));
        Claim oppose = new Claim(new ClaimId(UUID.randomUUID()), reviewId, RoleType.BACKEND, "authentication",
                ClaimSeverity.P1, ClaimPosition.OPPOSE, "Return policy", "Backend sees a contradiction", List.of(sharedEvidence));

        assertThat(new ConflictDetector().detect(List.of(support, oppose)).candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.rules())
                        .contains(ConflictDetector.ConflictRule.CONTRADICTORY_EVIDENCE));
    }

    private Claim claim(ReviewId reviewId, RoleType roleType, ClaimSeverity severity, ClaimPosition position) {
        return new Claim(new ClaimId(UUID.randomUUID()), reviewId, roleType, "authentication", severity, position,
                "Statement", "Reason", List.of());
    }
}
