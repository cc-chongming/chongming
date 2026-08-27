package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.EvidenceBlock;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-010#1.5] Accepts role-authored public Claims only after evidence ownership, lifecycle and idempotency checks.
 *
 * @author wangli
 */
@Service
public class ClaimService {

    private static final Logger LOG = LoggerFactory.getLogger(ClaimService.class);

    private final EvidenceLedgerService evidenceLedgerService;
    private final ReviewDebateStore debateStore;
    private final ReviewProtocolGuard protocolGuard;
    private final ReviewEventPublisher eventPublisher;
    private final ReviewDispatchService dispatchService;

    public ClaimService(
            EvidenceLedgerService evidenceLedgerService,
            ReviewDebateStore debateStore,
            ReviewProtocolGuard protocolGuard) {
        this(evidenceLedgerService, debateStore, protocolGuard, ReviewEventPublisher.noop(), null);
    }

    public ClaimService(
            EvidenceLedgerService evidenceLedgerService,
            ReviewDebateStore debateStore,
            ReviewProtocolGuard protocolGuard,
            ReviewEventPublisher eventPublisher) {
        this(evidenceLedgerService, debateStore, protocolGuard, eventPublisher, null);
    }

    @Autowired
    public ClaimService(
            EvidenceLedgerService evidenceLedgerService,
            ReviewDebateStore debateStore,
            ReviewProtocolGuard protocolGuard,
            ReviewEventPublisher eventPublisher,
            ReviewDispatchService dispatchService) {
        this.evidenceLedgerService = Objects.requireNonNull(evidenceLedgerService, "evidenceLedgerService must not be null");
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.protocolGuard = Objects.requireNonNull(protocolGuard, "protocolGuard must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.dispatchService = dispatchService;
    }

    /**
     * Stores a submitted Claim and returns the previously accepted Claim for a duplicate idempotency key.
     */
    public ClaimSubmissionResult submit(Review review, ClaimSubmission submission) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(submission, "submission must not be null");
        synchronized (review) {
            return submitSynchronized(review, submission);
        }
    }

    private ClaimSubmissionResult submitSynchronized(Review review, ClaimSubmission submission) {
        validateReviewIdentity(review, submission.metadata());
        String existingReference = review.commandResults().get(submission.metadata().idempotencyKey());
        if (existingReference != null) {
            Claim existing = debateStore.findClaim(review.id(), new ClaimId(UUID.fromString(existingReference)))
                    .orElseThrow(() -> new IllegalStateException("claim idempotency reference cannot be resolved"));
            return new ClaimSubmissionResult(existing, true);
        }
        ReviewDispatchCommand defenseCommand = validateReviewAndRole(review, submission.actorRole(), submission);
        requireExpectedVersion(review, submission.metadata());
        List<EvidenceReference> references = resolveEvidenceReferences(review.id(), submission.evidenceIds());
        Claim claim = protocolGuard.normalizeClaim(new Claim(
                new ClaimId(UUID.randomUUID()),
                review.id(),
                submission.actorRole(),
                submission.subjectKey(),
                submission.severity(),
                submission.position(),
                submission.statement(),
                submission.reasonSummary(),
                references));
        debateStore.saveClaim(claim);
        review.recordCommand(submission.metadata(), claim.claimId().value().toString());
        consumeDefenseCommand(review, defenseCommand);
        // [AIREVIEW-PLAN-040#1] Mount the accepted DEFENSE claim onto its topic so the court's
        // support side sees it; initial-review submissions (no dispatch command) never touch topics.
        attachDefenseClaimToTopic(review, defenseCommand, claim);
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ai.cc.chongming.review.domain.event.ReviewEventType.CLAIM_SUBMITTED,
                submission.actorRole(),
                null,
                null,
                claim.claimId(),
                null,
                null,
                40,
                Map.of("subjectKey", claim.subjectKey(), "severity", claim.severity().name())));
        return new ClaimSubmissionResult(claim, false);
    }

    /**
     * Returns all non-withdrawn Claims after the caller has completed the independent initial-review phase.
     */
    public List<Claim> publishInitialClaims(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.stage() != ReviewStage.INITIAL_REVIEW && review.stage() != ReviewStage.CONFLICT_DETECTION) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "claims can be published only after initial review begins");
        }
        if (!protocolGuard.validateDebateStart(review.roleActivations()).isValid()) {
            throw new ReviewDomainException(ReviewErrorCode.CORE_ROLE_INITIAL_REVIEW_REQUIRED,
                    "all activated core roles must complete initial review before Claims are published");
        }
        return debateStore.findClaims(review.id()).stream()
                .filter(claim -> claim.status() != ClaimStatus.WITHDRAWN)
                .toList();
    }

    private void validateReviewIdentity(Review review, ReviewCommandMetadata metadata) {
        if (!review.id().equals(metadata.reviewId())) {
            throw new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                    "claim command reviewId does not match aggregate");
        }
    }

    /**
     * Gates claim submission by review stage. INITIAL_REVIEW keeps the legacy behaviour (no
     * dispatch command needed). During a debate round the claim must carry a valid PENDING
     * DEFENSE dispatch command addressed to the submitting role, and its subjectKey must match
     * the subjectKey of the topic the command points at. Returns the validated command so the
     * caller can consume it after the claim committed.
     */
    private ReviewDispatchCommand validateReviewAndRole(
            Review review, RoleType actorRole, ClaimSubmission submission) {
        requireActiveClaimRole(review, actorRole);
        if (review.stage() == ReviewStage.INITIAL_REVIEW) {
            return null;
        }
        // [AIREVIEW-PLAN-047#1] The debate gate accepts the single DEBATE phase and tolerates the
        // legacy round stages so in-flight reviews can still submit DEFENSE claims.
        if (isDebateStage(review.stage())) {
            return requireDefenseDispatch(review, actorRole, submission);
        }
        throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                "claims may be submitted only during initial review or an active debate round");
    }

    /**
     * [AIREVIEW-PLAN-047#1] One shared phase predicate for the claim gate.
     */
    private static boolean isDebateStage(ReviewStage stage) {
        return stage == ReviewStage.DEBATE
                || stage == ReviewStage.DEBATE_ROUND_1
                || stage == ReviewStage.DEBATE_ROUND_2;
    }

    private void requireActiveClaimRole(Review review, RoleType actorRole) {
        boolean activeRole = review.roleActivations().stream()
                .anyMatch(activation -> activation.roleType() == actorRole);
        if (!activeRole || actorRole == RoleType.DIRECTOR || actorRole == RoleType.JUDGE) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "only an activated review role may submit a claim");
        }
    }

    private ReviewDispatchCommand requireDefenseDispatch(
            Review review, RoleType actorRole, ClaimSubmission submission) {
        if (dispatchService == null) {
            throw new IllegalStateException("DEFENSE claim submission requires a wired ReviewDispatchService");
        }
        if (submission.dispatchCommandId() == null) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "claims may be submitted during a debate round only with a valid DEFENSE dispatch command");
        }
        // Reuses the dispatch envelope validation: command exists, PENDING, unexpired, addressed
        // to the submitting role, allows exactly DEFENSE and matches the current debate round.
        ReviewDispatchCommand command = dispatchService.resolveForWrite(
                review, actorRole, submission.dispatchCommandId(), DispatchedAction.DEFENSE);
        requireDefenseSubjectKey(review, submission, command);
        return command;
    }

    private void requireDefenseSubjectKey(
            Review review, ClaimSubmission submission, ReviewDispatchCommand command) {
        if (command.topicId() == null) {
            throw new ReviewDomainException(ReviewErrorCode.TARGET_CLAIM_REQUIRED,
                    "a DEFENSE dispatch command requires topicId");
        }
        DebateTopic topic = debateStore.findTopic(review.id(), command.topicId())
                .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                        "DEFENSE dispatch topic does not belong to this review"));
        if (!topic.subjectKey().trim().equalsIgnoreCase(submission.subjectKey().trim())) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "DEFENSE claim subjectKey must match the dispatch topic subjectKey");
        }
    }

    private void consumeDefenseCommand(Review review, ReviewDispatchCommand command) {
        if (command != null && dispatchService != null) {
            dispatchService.consume(review, command);
        }
    }

    /**
     * [AIREVIEW-PLAN-040#1] Appends the accepted claim to the dispatch topic's membership and re-saves
     * the topic snapshot. The submission gate already verified the topic belongs to this review, so a
     * vanished topic is only a defensive warning: it must never roll back an accepted claim.
     */
    private void attachDefenseClaimToTopic(Review review, ReviewDispatchCommand defenseCommand, Claim claim) {
        if (defenseCommand == null || defenseCommand.topicId() == null) {
            return;
        }
        Optional<DebateTopic> topic = debateStore.findTopic(review.id(), defenseCommand.topicId());
        if (topic.isEmpty()) {
            LOG.warn("[AIREVIEW-PLAN-040#1] DEFENSE claim {} accepted but topic {} is no longer present; "
                    + "support-side mount skipped", claim.claimId().value(), defenseCommand.topicId().value());
            return;
        }
        DebateTopic mounted = topic.get();
        mounted.attachClaim(claim.claimId());
        debateStore.saveTopic(mounted);
    }

    private void requireExpectedVersion(Review review, ReviewCommandMetadata metadata) {
        if (metadata.expectedVersion() != review.version()) {
            throw new ReviewDomainException(ReviewErrorCode.VERSION_CONFLICT,
                    "expectedVersion does not match aggregate version");
        }
    }

    private List<EvidenceReference> resolveEvidenceReferences(ReviewId reviewId, List<EvidenceId> evidenceIds) {
        LinkedHashSet<EvidenceId> distinctIds = new LinkedHashSet<>(evidenceIds);
        Map<EvidenceId, EvidenceBlock> evidence = evidenceLedgerService.findByIds(reviewId, distinctIds);
        if (evidence.size() != distinctIds.size()) {
            throw new ReviewDomainException(ReviewErrorCode.INVALID_EVIDENCE,
                    "claim contains evidence that does not belong to this review");
        }
        return distinctIds.stream().map(evidenceId -> {
            EvidenceBlock block = evidence.get(evidenceId);
            return new EvidenceReference(
                    block.evidenceId(),
                    block.repositorySnapshotId().toString(),
                    block.snapshotRelativePath(),
                    block.lineNumber(),
                    block.excerptHash());
        }).toList();
    }

    /**
     * Typed Claim submission boundary; raw model output never reaches the store directly.
     *
     * @author wangli
     */
    public record ClaimSubmission(
            ReviewCommandMetadata metadata,
            RoleType actorRole,
            String subjectKey,
            ClaimSeverity severity,
            ClaimPosition position,
            String statement,
            String reasonSummary,
            List<EvidenceId> evidenceIds,
            CommandId dispatchCommandId) {

        public ClaimSubmission {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            requireText(subjectKey, "subjectKey");
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(position, "position must not be null");
            requireText(statement, "statement");
            requireText(reasonSummary, "reasonSummary");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }

        /** Initial-review submission without a dispatch command. */
        public ClaimSubmission(
                ReviewCommandMetadata metadata,
                RoleType actorRole,
                String subjectKey,
                ClaimSeverity severity,
                ClaimPosition position,
                String statement,
                String reasonSummary,
                List<EvidenceId> evidenceIds) {
            this(metadata, actorRole, subjectKey, severity, position, statement, reasonSummary,
                    evidenceIds, null);
        }
    }

    /**
     * Submission output with an explicit idempotent-replay indicator.
     *
     * @author wangli
     */
    public record ClaimSubmissionResult(Claim claim, boolean replayed) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
