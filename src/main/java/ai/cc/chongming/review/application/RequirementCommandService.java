package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RemoteRepositorySource;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.protocol.RequirementLifecycleStateMachine;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.ReviewRequirementLinkStore;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryUrlValidator;
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
    private final RemoteTokenCipher remoteTokenCipher;
    private final RemoteRepositoryUrlValidator remoteUrlValidator;
    private final RequirementLifecycleStateMachine stateMachine = new RequirementLifecycleStateMachine();

    public RequirementCommandService(
            RequirementRepository requirementRepository,
            ReviewerIdentityProvider identityProvider) {
        this(requirementRepository, identityProvider, (reviewId, requirementId) -> true);
    }

    public RequirementCommandService(
            RequirementRepository requirementRepository,
            ReviewerIdentityProvider identityProvider,
            ReviewRequirementLinkStore reviewRequirementLinkStore) {
        this(requirementRepository, identityProvider, reviewRequirementLinkStore, null, null);
    }

    @Autowired
    public RequirementCommandService(
            RequirementRepository requirementRepository,
            ReviewerIdentityProvider identityProvider,
            ReviewRequirementLinkStore reviewRequirementLinkStore,
            RemoteTokenCipher remoteTokenCipher,
            RemoteRepositoryUrlValidator remoteUrlValidator) {
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider must not be null");
        this.reviewRequirementLinkStore = Objects.requireNonNull(
                reviewRequirementLinkStore, "reviewRequirementLinkStore must not be null");
        this.remoteTokenCipher = remoteTokenCipher;
        this.remoteUrlValidator = remoteUrlValidator;
    }

    @Transactional
    public Requirement create(CreateRequirementCommand command) {
        CreateRequirementCommand validatedCommand = Objects.requireNonNull(command, "command must not be null");
        // [AIREVIEW-PLAN-027] The controller-supplied creator wins so the authenticated principal
        // becomes the owner; without one the historical identity provider keeps demo behaviour.
        String creatorId = validatedCommand.creatorUsername() == null || validatedCommand.creatorUsername().isBlank()
                ? identityProvider.currentReviewer().reviewerId()
                : validatedCommand.creatorUsername().trim();
        RemoteRepositorySource remoteSource = resolveRemoteSource(validatedCommand.remote(), null);
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()),
                validatedCommand.title(),
                validatedCommand.description(),
                creatorId,
                validatedCommand.assigneeId(),
                validatedCommand.repositoryPath(),
                remoteSource,
                validatedCommand.priority());
        requirementRepository.save(requirement);
        return requirement;
    }

    @Transactional
    public Requirement revise(RequirementId requirementId, ReviseRequirementCommand command) {
        Requirement requirement = require(requirementId);
        ReviseRequirementCommand validatedCommand = Objects.requireNonNull(command, "command must not be null");
        RemoteRepositorySource remoteSource = resolveRemoteSource(validatedCommand.remote(), requirement.remoteSource());
        requirement.revise(
                validatedCommand.title(),
                validatedCommand.description(),
                validatedCommand.assigneeId(),
                validatedCommand.repositoryPath(),
                remoteSource,
                validatedCommand.priority(),
                validatedCommand.expectedVersion());
        requirementRepository.save(requirement);
        return requirement;
    }

    /**
     * [AIREVIEW-PLAN-029] Validates and normalizes one requirement-supplied online repository
     * source. A {@code null} command clears the binding; a blank token keeps the previous cipher
     * text only when the url and ref are unchanged. The configured-repository identity and the
     * online source are mutually exclusive.
     */
    private RemoteRepositorySource resolveRemoteSource(RemoteSourceCommand command, RemoteRepositorySource existing) {
        if (command == null) {
            return null;
        }
        String url = command.url() == null ? "" : command.url().trim();
        if (url.isEmpty()) {
            return null;
        }
        if (remoteUrlValidator == null || remoteTokenCipher == null) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID, "线上仓库接入能力在当前环境不可用");
        }
        try {
            remoteUrlValidator.requireSafe(url);
        } catch (RepositoryAccessException exception) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID, "线上仓库地址不合法或不被允许");
        }
        String ref = command.ref() == null || command.ref().isBlank() ? null : command.ref().trim();
        String token = command.token() == null ? null : command.token().trim();
        String encryptedToken;
        if (token != null && !token.isEmpty()) {
            encryptedToken = remoteTokenCipher.encrypt(token);
        } else if (existing != null && url.equals(existing.url()) && Objects.equals(ref, existing.ref())) {
            encryptedToken = existing.encryptedToken();
        } else {
            encryptedToken = null;
        }
        return new RemoteRepositorySource(url, ref, encryptedToken);
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

    /**
     * [AIREVIEW-PLAN-021#2] Permanently removes one user-visible requirement while retaining its
     * independent review history.  The reverse review link is released before the command returns.
     */
    @Transactional
    public void delete(RequirementId requirementId, long expectedVersion) {
        Requirement requirement = require(requirementId);
        synchronized (requirement) {
            requirement.requireExpectedVersion(expectedVersion);
            if (!requirementRepository.delete(requirementId, expectedVersion)) {
                throw new RequirementDomainException(
                        RequirementErrorCode.VERSION_CONFLICT,
                        "requirement version no longer matches the persisted aggregate");
            }
            reviewRequirementLinkStore.unlinkRequirement(requirementId);
        }
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
            String title,
            String description,
            String assigneeId,
            String repositoryPath,
            String priority,
            String creatorUsername,
            RemoteSourceCommand remote) {

        /**
         * [AIREVIEW-PLAN-027] Legacy constructor without an authenticated creator; the identity
         * provider fallback applies.
         */
        public CreateRequirementCommand(
                String title, String description, String assigneeId, String repositoryPath, String priority) {
            this(title, description, assigneeId, repositoryPath, priority, null, null);
        }

        /** [AIREVIEW-PLAN-028-era] Constructor without the online repository source. */
        public CreateRequirementCommand(
                String title, String description, String assigneeId, String repositoryPath, String priority,
                String creatorUsername) {
            this(title, description, assigneeId, repositoryPath, priority, creatorUsername, null);
        }
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
            long expectedVersion,
            RemoteSourceCommand remote) {

        /** [AIREVIEW-PLAN-029] Legacy revision without the online repository source. */
        public ReviseRequirementCommand(
                String title, String description, String assigneeId, String repositoryPath, String priority,
                long expectedVersion) {
            this(title, description, assigneeId, repositoryPath, priority, expectedVersion, null);
        }
    }

    /**
     * [AIREVIEW-PLAN-029] Caller-supplied online repository binding; {@code token} is plain text
     * that never survives command processing.
     *
     * @author wangli
     */
    public record RemoteSourceCommand(String url, String ref, String token) {
    }
}
