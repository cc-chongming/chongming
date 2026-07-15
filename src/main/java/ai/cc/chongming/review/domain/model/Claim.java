package ai.cc.chongming.review.domain.model;

import java.util.List;
import java.util.Objects;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-003#1.2] Represents a public, evidence-backed review assertion.
 *
 * @author wangli
 */
public record Claim(
        ClaimId claimId,
        ReviewId reviewId,
        RoleType roleType,
        String subjectKey,
        ClaimSeverity severity,
        ClaimPosition position,
        String statement,
        String reasonSummary,
        List<EvidenceReference> evidenceReferences,
        ClaimStatus status) {

    public Claim(
            ClaimId claimId,
            ReviewId reviewId,
            RoleType roleType,
            String subjectKey,
            ClaimSeverity severity,
            ClaimPosition position,
            String statement,
            String reasonSummary,
            List<EvidenceReference> evidenceReferences) {
        this(claimId, reviewId, roleType, subjectKey, severity, position, statement, reasonSummary, evidenceReferences,
                ClaimStatus.SUBMITTED);
    }

    public Claim {
        Objects.requireNonNull(claimId, "claimId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        requireText(subjectKey, "subjectKey");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(position, "position must not be null");
        requireText(statement, "statement");
        requireText(reasonSummary, "reasonSummary");
        evidenceReferences = List.copyOf(evidenceReferences);
        Objects.requireNonNull(status, "status must not be null");
    }

    public Claim withStatus(ClaimStatus nextStatus) {
        return new Claim(claimId, reviewId, roleType, subjectKey, severity, position, statement, reasonSummary,
                evidenceReferences, nextStatus);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

