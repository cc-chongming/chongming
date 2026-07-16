package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.EvidenceBlock;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

    private final EvidenceLedgerService evidenceLedgerService;
    private final ReviewDebateStore debateStore;
    private final ReviewProtocolGuard protocolGuard;
    private final ReviewEventPublisher eventPublisher;

    public ClaimService(
            EvidenceLedgerService evidenceLedgerService,
            ReviewDebateStore debateStore,
            ReviewProtocolGuard protocolGuard) {
        this(evidenceLedgerService, debateStore, protocolGuard, ReviewEventPublisher.noop());
    }

    @Autowired
    public ClaimService(
            EvidenceLedgerService evidenceLedgerService,
            ReviewDebateStore debateStore,
            ReviewProtocolGuard protocolGuard,
            ReviewEventPublisher eventPublisher) {
        this.evidenceLedgerService = Objects.requireNonNull(evidenceLedgerService, "evidenceLedgerService must not be null");
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.protocolGuard = Objects.requireNonNull(protocolGuard, "protocolGuard must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /**
     * Stores a submitted Claim and returns the previously accepted Claim for a duplicate idempotency key.
     */
    public ClaimSubmissionResult submit(Review review, ClaimSubmission submission) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(submission, "submission must not be null");
        validateReviewAndRole(review, submission.metadata(), submission.actorRole());
        String existingReference = review.commandResults().get(submission.metadata().idempotencyKey());
        if (existingReference != null) {
            Claim existing = debateStore.findClaim(review.id(), new ClaimId(UUID.fromString(existingReference)))
                    .orElseThrow(() -> new IllegalStateException("claim idempotency reference cannot be resolved"));
            return new ClaimSubmissionResult(existing, true);
        }
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
review.completeInitialReview(submission.actorRole());
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

    private void validateReviewAndRole(Review review, ReviewCommandMetadata metadata, RoleType actorRole) {
        if (!review.id().equals(metadata.reviewId())) {
            throw new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                    "claim command reviewId does not match aggregate");
        }
        if (review.stage() != ReviewStage.INITIAL_REVIEW) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "claims may be submitted only during initial review");
        }
        boolean activeRole = review.roleActivations().stream()
                .anyMatch(activation -> activation.roleType() == actorRole);
        if (!activeRole || actorRole == RoleType.DIRECTOR || actorRole == RoleType.JUDGE) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "only an activated review role may submit a claim");
        }
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
            List<EvidenceId> evidenceIds) {

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
