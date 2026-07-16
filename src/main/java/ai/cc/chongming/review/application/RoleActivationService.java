package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ValidationResult;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Approves dynamic role activation through the protocol guard before the runtime creates a subagent.
 *
 * @author wangli
 */
@Service
public class RoleActivationService {

    private final ReviewProtocolGuard protocolGuard;
    private final RolePackRegistry rolePackRegistry;
    private final ReviewProperties reviewProperties;

    public RoleActivationService(
            ReviewProtocolGuard protocolGuard, RolePackRegistry rolePackRegistry, ReviewProperties reviewProperties) {
        this.protocolGuard = Objects.requireNonNull(protocolGuard, "protocolGuard must not be null");
        this.rolePackRegistry = Objects.requireNonNull(rolePackRegistry, "rolePackRegistry must not be null");
        this.reviewProperties = Objects.requireNonNull(reviewProperties, "reviewProperties must not be null");
    }

    /**
     * Validates a request and returns a receipt. Callers apply it only after role runtime creation succeeds.
     */
    public ActivationReceipt approve(Review review, ReviewRuntimeContext runtime, ActivationRequest request) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!review.id().equals(runtime.reviewId()) || review.attemptNo() != runtime.attemptNo()) {
            throw new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                    "runtime context must identify the current review attempt");
        }
        if (request.roleType() == RoleType.DIRECTOR) {
            throw new ReviewDomainException(ReviewErrorCode.DUPLICATE_SUBMISSION,
                    "director is created once per review attempt and cannot be activated as a role");
        }
        if (review.roleActivations().size() >= reviewProperties.maxAgents()) {
            throw new ReviewDomainException(ReviewErrorCode.AGENT_LIMIT_EXCEEDED,
                    "configured review agent limit has been reached");
        }
        ValidationResult guardResult = protocolGuard.validateRoleActivation(review.roleActivations(), request.roleType());
        if (!guardResult.isValid()) {
            throw new ReviewDomainException(guardResult.errorCode(), "role activation was rejected by protocol guard");
        }
        RolePack rolePack = rolePackRegistry.require(request.roleType());
        return new ActivationReceipt(
                new RoleActivation(request.roleType(), runtime.roleLabel(request.roleType()), false),
                rolePack,
                request.source(),
                request.reason(),
                request.evidenceIds(),
                Instant.now());
    }

    /**
     * Applies an already approved role activation after runtime creation has succeeded.
     */
    public void apply(Review review, ActivationReceipt receipt) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(receipt, "receipt must not be null");
        review.activateRole(receipt.activation());
    }

    /**
     * Dynamic activation command supplied by the director, a deterministic rule, or a human.
     *
     * @author wangli
     */
    public record ActivationRequest(RoleType roleType, ActivationSource source, String reason, List<EvidenceId> evidenceIds) {

        public ActivationRequest {
            Objects.requireNonNull(roleType, "roleType must not be null");
            Objects.requireNonNull(source, "source must not be null");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /**
     * Traceable source for a role activation.
     *
     * @author wangli
     */
    public enum ActivationSource {
        PLAN,
        RULE,
        HUMAN
    }

    /**
     * Immutable validated activation, including the fixed RolePack and evidence references.
     *
     * @author wangli
     */
    public record ActivationReceipt(
            RoleActivation activation,
            RolePack rolePack,
            ActivationSource source,
            String reason,
            List<EvidenceId> evidenceIds,
            Instant approvedAt) {

        public ActivationReceipt {
            Objects.requireNonNull(activation, "activation must not be null");
            Objects.requireNonNull(rolePack, "rolePack must not be null");
            Objects.requireNonNull(source, "source must not be null");
            evidenceIds = List.copyOf(evidenceIds);
            Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        }
    }
}
