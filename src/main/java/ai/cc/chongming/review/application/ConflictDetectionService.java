package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.debate.ConflictDetector;
import ai.cc.chongming.review.domain.debate.ConflictDetector.ConflictCandidate;
import ai.cc.chongming.review.domain.debate.ConflictDetector.ConflictDetectionResult;
import ai.cc.chongming.review.domain.debate.ConflictDetector.NoConflictReason;
import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewConflictAudit;
import ai.cc.chongming.review.domain.model.ReviewConflictAudit.Disposition;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewAssessmentStore;
import ai.cc.chongming.review.domain.repository.ReviewConflictAuditStore;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * [AIREVIEW-PLAN-024#方案4] Production conflict detection front-end. Aggregates persisted
 * Assessments and Claims by stable subject key, delegates the deterministic rules to
 * {@link ConflictDetector}, and keeps a one-to-one audit trail between every detected candidate
 * subject and its later disposition (registered as a debate topic or explicitly skipped). A single
 * GAP or UNKNOWN assessment is surfaced only as a Gate risk input and never forms a debate topic.
 *
 * <p>Audit records are persisted through a batch store so detection dispositions survive service or
 * process reconstruction; every count is derived from single batch store reads so report consumers
 * never hand-write numbers.
 *
 * @author zyj
 */
@Service
public class ConflictDetectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConflictDetectionService.class);

    private final ReviewAssessmentStore assessmentStore;
    private final ReviewDebateStore debateStore;
    private final ReviewConflictAuditStore auditStore;
    private final ConflictDetector conflictDetector;

    @Autowired
    public ConflictDetectionService(
            ReviewAssessmentStore assessmentStore,
            ReviewDebateStore debateStore,
            ReviewConflictAuditStore auditStore) {
        this(assessmentStore, debateStore, auditStore, new ConflictDetector());
    }

    public ConflictDetectionService(
            ReviewAssessmentStore assessmentStore,
            ReviewDebateStore debateStore,
            ReviewConflictAuditStore auditStore,
            ConflictDetector conflictDetector) {
        this.assessmentStore = Objects.requireNonNull(assessmentStore, "assessmentStore must not be null");
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore must not be null");
        this.conflictDetector = Objects.requireNonNull(conflictDetector, "conflictDetector must not be null");
    }

    /**
     * Runs deterministic detection over the persisted facts of one review attempt and refreshes the
     * audit trail: every candidate subject is recorded DETECTED and every conflict-free subject is
     * recorded NO_CONFLICT so a later registration/skip decision always has a counterpart record.
     */
    public Outcome detect(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        synchronized (review) {
            if (review.stage() != ReviewStage.CONFLICT_DETECTION) {
                throw new ReviewDomainException(
                        ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                        "conflict detection is only available in CONFLICT_DETECTION");
            }
            return detectLocked(review);
        }
    }

    private Outcome detectLocked(Review review) {
        List<Claim> claims = debateStore.findClaims(review.id());
        List<ReviewAssessment> assessments = assessmentStore.findByReview(review.id(), review.attemptNo());
        ConflictDetectionResult result = conflictDetector.detect(claims, assessments);
        List<ReviewConflictAudit> records = new ArrayList<>();
        for (ConflictCandidate candidate : result.candidates()) {
            records.add(new ReviewConflictAudit(
                    review.id(), review.attemptNo(), candidate.subjectKey(), candidate.claimIds(),
                    candidate.explanation(), Disposition.DETECTED, Instant.now()));
        }
        for (NoConflictReason reason : result.noConflicts()) {
            records.add(new ReviewConflictAudit(
                    review.id(), review.attemptNo(), reason.subjectKey(), List.of(), reason.reason(),
                    Disposition.NO_CONFLICT, Instant.now()));
        }
        auditStore.replaceBatch(review.id(), review.attemptNo(), records);
        List<ReviewAssessment> gateRisks = assessments.stream()
                .filter(assessment -> assessment.status() == AssessmentStatus.GAP
                        || assessment.status() == AssessmentStatus.UNKNOWN)
                .toList();
        LOGGER.info("CONFLICT_DETECTION_COMPLETED reviewId={} attemptNo={} candidates={} noConflictSubjects={} gateRisks={}",
                review.id().value(), review.attemptNo(), result.candidates().size(),
                result.noConflicts().size(), gateRisks.size());
        return new Outcome(result, gateRisks, auditRecords(review.id(), review.attemptNo()));
    }

    /**
     * Completes the one-to-one audit trail after the Director has registered topics (or skipped the
     * debate): every previously DETECTED candidate subject whose normalized key was registered becomes
     * REGISTERED, every remaining candidate subject becomes SKIPPED. NO_CONFLICT subjects keep their
     * disposition because they were never registrable.
     */
    public void recordTopicRegistration(Review review, Collection<String> registeredSubjectKeys) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(registeredSubjectKeys, "registeredSubjectKeys must not be null");
        synchronized (review) {
            Set<String> normalizedRegistered = registeredSubjectKeys.stream()
                    .filter(Objects::nonNull)
                    .map(ReviewConflictAudit::normalizeSubjectKey)
                    .collect(java.util.stream.Collectors.toSet());
            auditStore.finalizeAttempt(review.id(), review.attemptNo(), normalizedRegistered, Instant.now());
            LOGGER.info("CONFLICT_AUDIT_FINALIZED reviewId={} attemptNo={} registered={}",
                    review.id().value(), review.attemptNo(), normalizedRegistered);
        }
    }

    /**
     * Deterministic snapshot of the audit trail of one review, ordered by subject key.
     */
    public List<ReviewConflictAudit> auditRecords(ReviewId reviewId, int attemptNo) {
        return auditStore.findByReviewAttempt(
                Objects.requireNonNull(reviewId, "reviewId must not be null"), attemptNo);
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Debate counts derived from single batch store reads only, ready for
     * report consumption in 方案5: conflict candidates, registered topics, remaining risks and
     * unclosed actions. Models must never hand-write these numbers.
     */
    public DebateMetrics debateMetrics(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        List<Claim> claims = debateStore.findClaims(review.id());
        List<ReviewAssessment> assessments = assessmentStore.findByReview(review.id(), review.attemptNo());
        List<DebateTopic> topics = debateStore.findTopics(review.id());
        List<DebateTurn> turns = debateStore.findTurns(review.id());
        ConflictDetectionResult result = conflictDetector.detect(claims, assessments);
        Set<ClaimId> coveredClaimIds = topics.stream()
                .flatMap(topic -> topic.claimIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        long remainingRisks = assessments.stream()
                .filter(assessment -> assessment.status() == AssessmentStatus.GAP
                        || assessment.status() == AssessmentStatus.UNKNOWN)
                .count()
                + claims.stream()
                        .filter(claim -> claim.position() == ClaimPosition.OPPOSE
                                && claim.status() != ClaimStatus.WITHDRAWN
                                && !coveredClaimIds.contains(claim.claimId()))
                        .count();
        Map<ai.cc.chongming.review.domain.model.ReviewTypes.TopicId, List<DebateTurn>> turnsByTopic =
                new LinkedHashMap<>();
        for (DebateTurn turn : turns) {
            turnsByTopic.computeIfAbsent(turn.topicId(), ignored -> new ArrayList<>()).add(turn);
        }
        int unclosedActions = 0;
        for (DebateTopic topic : topics) {
            if (topic.status().isTerminal()) {
                continue;
            }
            if (topic.status() == ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus.OPEN
                    || topic.status() == ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus.CHALLENGED) {
                unclosedActions++;
            }
            unclosedActions += unansweredEvidenceRequests(turnsByTopic.getOrDefault(topic.id(), List.of()));
        }
        return new DebateMetrics(result.candidates().size(), topics.size(), (int) remainingRisks, unclosedActions);
    }

    private int unansweredEvidenceRequests(List<DebateTurn> turns) {
        int unanswered = 0;
        for (int index = 0; index < turns.size(); index++) {
            DebateTurn turn = turns.get(index);
            if (turn.turnType() != DebateTurnType.EVIDENCE_REQUEST || turn.targetRole() == null) {
                continue;
            }
            boolean answered = false;
            for (int later = index + 1; later < turns.size(); later++) {
                if (turns.get(later).actorRole() == turn.targetRole()) {
                    answered = true;
                    break;
                }
            }
            if (!answered) {
                unanswered++;
            }
        }
        return unanswered;
    }

    /**
     * Detection output consumed by the production flow: deterministic candidates, Gate risk inputs
     * (single GAP/UNKNOWN assessments) and the current audit snapshot.
     *
 * @author zyj
     */
    public record Outcome(
            ConflictDetectionResult result,
            List<ReviewAssessment> gateRiskAssessments,
            List<ReviewConflictAudit> auditRecords) {
        public Outcome {
            Objects.requireNonNull(result, "result must not be null");
            gateRiskAssessments = List.copyOf(gateRiskAssessments);
            auditRecords = List.copyOf(auditRecords);
        }
    }

    /**
     * Store-derived debate counters; never hand-written by a model.
     *
 * @author zyj
     */
    public record DebateMetrics(
            int conflictCandidateCount,
            int registeredTopicCount,
            int remainingRiskCount,
            int unclosedActionCount) {
    }
}
