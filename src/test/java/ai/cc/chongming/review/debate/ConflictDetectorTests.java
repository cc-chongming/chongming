package ai.cc.chongming.review.debate;

import ai.cc.chongming.review.domain.debate.ConflictDetector;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import java.time.Instant;
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

    /** [AIREVIEW-PLAN-024#方案4] 同一检查点 CONFIRMED 与 GAP 结论相互矛盾才形成候选。 */
    @Test
    void recallsContradictoryAssessmentConclusionsOnTheSameCheckpoint() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ConflictDetector.ConflictDetectionResult result = new ConflictDetector().detect(List.of(), List.of(
                assessment(reviewId, RoleType.PRODUCT, AssessmentStatus.CONFIRMED, "auth.token_policy"),
                assessment(reviewId, RoleType.BACKEND, AssessmentStatus.GAP, "auth.token_policy")));

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.subjectKey()).isEqualTo("auth.token_policy");
            assertThat(candidate.claimIds()).isEmpty();
            assertThat(candidate.rules()).containsExactly(ConflictDetector.ConflictRule.ASSESSMENT_STATUS_CONFLICT);
        });
    }

    /** [AIREVIEW-PLAN-024#方案4] 单个 GAP 只是 Gate 风险输入，不自动形成辩题。 */
    @Test
    void loneGapOrUnknownAssessmentIsNeverAConflictCandidate() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ConflictDetector.ConflictDetectionResult result = new ConflictDetector().detect(List.of(), List.of(
                assessment(reviewId, RoleType.BACKEND, AssessmentStatus.GAP, "auth.refresh_flow"),
                assessment(reviewId, RoleType.FRONTEND, AssessmentStatus.UNKNOWN, "auth.session_ui")));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.noConflicts())
                .extracting(ConflictDetector.NoConflictReason::subjectKey)
                .containsExactlyInAnyOrder("auth.refresh_flow", "auth.session_ui");
    }

    private Claim claim(ReviewId reviewId, RoleType roleType, ClaimSeverity severity, ClaimPosition position) {
        return new Claim(new ClaimId(UUID.randomUUID()), reviewId, roleType, "authentication", severity, position,
                "Statement", "Reason", List.of());
    }

    private ReviewAssessment assessment(ReviewId reviewId, RoleType roleType, AssessmentStatus status, String checkpointKey) {
        return new ReviewAssessment(reviewId, 1, roleType, checkpointKey, status, "结论摘要",
                status.requiresReasonSummary() ? "风险原因说明" : "", List.of(),
                ReviewAssessment.idempotencyKeyFor(reviewId, 1, roleType, checkpointKey), Instant.now());
    }
}
