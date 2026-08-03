package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.protocol.RequirementLifecycleStateMachine;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.ReviewRequirementLinkStore;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#2] Applies requirement commands through its lifecycle aggregate.
 *
 * @author zyj
 */
@Service
public class RequirementCommandService {

    private final RequirementRepository requirementRepository;
    private final ReviewerIdentityProvider identityProvider;
    private final ReviewRequirementLinkStore reviewRequirementLinkStore;
    private final RequirementLifecycleStateMachine stateMachine = new RequirementLifecycleStateMachine();

    public RequirementCommandService(
            RequirementRepository requirementRepository,
            ReviewerIdentityProvider identityProvider) {
        this(requirementRepository, identityProvider, (reviewId, requirementId) -> true);
    }

    @Autowired
    public RequirementCommandService(
            RequirementRepository requirementRepository,
            ReviewerIdentityProvider identityProvider,
            ReviewRequirementLinkStore reviewRequirementLinkStore) {
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider must not be null");
        this.reviewRequirementLinkStore = Objects.requireNonNull(
                reviewRequirementLinkStore, "reviewRequirementLinkStore must not be null");
    }

    @Transactional
    public Requirement create(CreateRequirementCommand command) {
        CreateRequirementCommand validatedCommand = Objects.requireNonNull(command, "command must not be null");
        String creatorId = identityProvider.currentReviewer().reviewerId();
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()),
                validatedCommand.title(),
                validatedCommand.description(),
                creatorId,
                validatedCommand.assigneeId(),
                validatedCommand.repositoryPath(),
                validatedCommand.priority());
        requirementRepository.save(requirement);
        return requirement;
    }

    @Transactional
    public Requirement revise(RequirementId requirementId, ReviseRequirementCommand command) {
        Requirement requirement = require(requirementId);
        ReviseRequirementCommand validatedCommand = Objects.requireNonNull(command, "command must not be null");
        requirement.revise(
                validatedCommand.title(),
                validatedCommand.description(),
                validatedCommand.assigneeId(),
                validatedCommand.repositoryPath(),
                validatedCommand.priority(),
                validatedCommand.expectedVersion());
        requirementRepository.save(requirement);
        return requirement;
    }

    @Transactional
    public Requirement submitForReview(RequirementId requirementId, ReviewId reviewId, long expectedVersion) {
        Requirement requirement = require(requirementId);
        synchronized (requirement) {
            requirement.requireExpectedVersion(expectedVersion);
            ReviewId targetReviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
            requirement.validateSubmissionForReview(targetReviewId, stateMachine);
            if (!reviewRequirementLinkStore.tryBindPendingReview(targetReviewId, requirementId)) {
                throw new RequirementDomainException(
                        RequirementErrorCode.REVIEW_ALREADY_BOUND,
                        "review must be pending and must not be bound to another requirement");
            }
            requirement.submitForReview(targetReviewId, stateMachine);
            requirementRepository.save(requirement);
            return requirement;
        }
    }

    @Transactional
    public Requirement markReviewStarted(ReviewId reviewId) {
        Requirement requirement = requirementRepository.findByReviewId(Objects.requireNonNull(reviewId, "reviewId must not be null"))
                .orElseThrow(() -> new RequirementDomainException(
                        RequirementErrorCode.REQUIREMENT_NOT_FOUND,
                        "requirement for review was not found"));
        if (requirement.status() == RequirementStatus.PENDING_REVIEW) {
            requirement.transitionTo(RequirementStatus.REVIEWING, stateMachine);
            requirementRepository.save(requirement);
        }
        return requirement;
    }

    @Transactional
    public Requirement applyGateDecision(ReviewId reviewId, GateResult result) {
        Requirement requirement = requirementRepository.findByReviewId(Objects.requireNonNull(reviewId, "reviewId must not be null"))
                .orElseThrow(() -> new RequirementDomainException(
                        RequirementErrorCode.REQUIREMENT_NOT_FOUND,
                        "requirement for review was not found"));
        RequirementStatus target = switch (Objects.requireNonNull(result, "result must not be null")) {
            case AI_PASS, CONDITIONAL, PASS, OVERRIDE -> RequirementStatus.APPROVED;
            case BLOCK -> RequirementStatus.REJECTED;
            case RETURN -> RequirementStatus.RETURNED;
            case HUMAN_REQUIRED -> null;
        };
        if (target != null && requirement.status() == RequirementStatus.REVIEWING) {
            requirement.transitionTo(target, stateMachine);
            requirementRepository.save(requirement);
        }
        return requirement;
    }

    @Transactional
    public Requirement startDevelopment(RequirementId requirementId, long expectedVersion) {
        return transition(requirementId, expectedVersion, RequirementStatus.DEVELOPING);
    }

    @Transactional
    public Requirement complete(RequirementId requirementId, long expectedVersion) {
        return transition(requirementId, expectedVersion, RequirementStatus.DONE);
    }

    @Transactional
    public Requirement cancel(RequirementId requirementId, long expectedVersion) {
        return transition(requirementId, expectedVersion, RequirementStatus.CANCELLED);
    }

    private Requirement transition(RequirementId requirementId, long expectedVersion, RequirementStatus target) {
        Requirement requirement = require(requirementId);
        requirement.requireExpectedVersion(expectedVersion);
        requirement.transitionTo(target, stateMachine);
        requirementRepository.save(requirement);
        return requirement;
    }

    private Requirement require(RequirementId requirementId) {
        return requirementRepository.findById(Objects.requireNonNull(requirementId, "requirementId must not be null"))
                .orElseThrow(() -> new RequirementDomainException(
                        RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement was not found"));
    }

    /**
     * @author zyj
     */
    public record CreateRequirementCommand(
            String title, String description, String assigneeId, String repositoryPath, String priority) {
    }

    /**
     * @author zyj
     */
    public record ReviseRequirementCommand(
            String title,
            String description,
            String assigneeId,
            String repositoryPath,
            String priority,
            long expectedVersion) {
    }
}
