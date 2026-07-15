package ai.cc.chongming.review.domain.protocol;

import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-003#1.5,#1.6] Centralizes deterministic role, evidence, Gate and command rules.
 *
 * @author wangli
 */
public final class ReviewProtocolGuard {

    private static final int MAX_AGENT_COUNT = 8;
    private static final int MAX_OPTIONAL_ROLE_COUNT = 3;
    private static final Set<RoleType> CORE_ROLES = EnumSet.of(
            RoleType.PRODUCT, RoleType.PROJECT, RoleType.FRONTEND, RoleType.BACKEND);

    public ValidationResult validateDebateStart(Collection<RoleActivation> activations) {
        Set<RoleType> completedCoreRoles = EnumSet.noneOf(RoleType.class);
        for (RoleActivation activation : activations) {
            if (activation.initialReviewCompleted() && activation.roleType().isCore()) {
                completedCoreRoles.add(activation.roleType());
            }
        }
        return completedCoreRoles.containsAll(CORE_ROLES)
                ? ValidationResult.valid()
                : ValidationResult.invalid(ReviewErrorCode.CORE_ROLE_INITIAL_REVIEW_REQUIRED);
    }

    public ValidationResult validateRoleActivation(Collection<RoleActivation> activations, RoleType requestedRole) {
        Objects.requireNonNull(activations, "activations must not be null");
        Objects.requireNonNull(requestedRole, "requestedRole must not be null");
        if (activations.size() >= MAX_AGENT_COUNT) {
            return ValidationResult.invalid(ReviewErrorCode.AGENT_LIMIT_EXCEEDED);
        }
        if (requestedRole.isOptional()
                && activations.stream().filter(activation -> activation.roleType().isOptional()).count()
                >= MAX_OPTIONAL_ROLE_COUNT) {
            return ValidationResult.invalid(ReviewErrorCode.AGENT_LIMIT_EXCEEDED);
        }
        if (activations.stream().anyMatch(activation -> activation.roleType() == requestedRole)) {
            return ValidationResult.invalid(ReviewErrorCode.DUPLICATE_SUBMISSION);
        }
        return ValidationResult.valid();
    }

    public Claim normalizeClaim(Claim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        if (claim.severity().requiresEvidenceForAutoBlock() && claim.evidenceReferences().isEmpty()) {
            return claim.withStatus(ClaimStatus.UNVERIFIED);
        }
        return claim;
    }

    public GateResult normalizeAiGateDraft(GateResult proposed, Collection<Claim> claims) {
        Objects.requireNonNull(proposed, "proposed must not be null");
        Objects.requireNonNull(claims, "claims must not be null");
        boolean hasUnverifiedHighSeverityClaim = claims.stream().anyMatch(claim ->
                claim.status() == ClaimStatus.UNVERIFIED && claim.severity().requiresEvidenceForAutoBlock());
        return proposed == GateResult.BLOCK && hasUnverifiedHighSeverityClaim ? GateResult.HUMAN_REQUIRED : proposed;
    }

    public ValidationResult validateGateDecision(DecisionActor actor, DecisionStatus status) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status == DecisionStatus.FINAL && actor != DecisionActor.HUMAN) {
            return ValidationResult.invalid(ReviewErrorCode.FINAL_GATE_REQUIRES_HUMAN);
        }
        return ValidationResult.valid();
    }

    public ProtocolCommandResult executeCommand(
            Review review,
            ReviewCommandMetadata metadata,
            String resultReference,
            String eventType) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        return new ProtocolCommandResult(review.recordCommand(metadata, resultReference), eventType);
    }
}

