package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.gate.GatePolicy;

import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-010#1.5] Accepts a Judge conclusion that may select existing facts but never introduce a Claim, Evidence or final Gate result.
 *
 * @author wangli
 */
@Service
public class JudgeService {

    private final ReviewDebateStore debateStore;
    private final GatePolicy gatePolicy;
    private final ReviewEventPublisher eventPublisher;

    public JudgeService(ReviewDebateStore debateStore) {
        this(debateStore, new GatePolicy(), ReviewEventPublisher.noop());
    }

    @Autowired
    public JudgeService(ReviewDebateStore debateStore, GatePolicy gatePolicy, ReviewEventPublisher eventPublisher) {
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.gatePolicy = Objects.requireNonNull(gatePolicy, "gatePolicy must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /** Saves one Judge conclusion for a terminal topic and returns an idempotent replay when applicable. */
    public JudgeResult submitJudgement(Review review, JudgeSubmission submission) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(submission, "submission must not be null");
        if (!review.id().equals(submission.metadata().reviewId())) {
            throw new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                    "judge command reviewId does not match aggregate");
        }
        String existing = review.commandResults().get(submission.metadata().idempotencyKey());
        if (existing != null) {
            TopicId topicId = new TopicId(UUID.fromString(existing));
            JudgeDecision decision = debateStore.findJudgeDecision(review.id(), topicId)
                    .orElseThrow(() -> new IllegalStateException("judge idempotency reference cannot be resolved"));
            return new JudgeResult(decision, true);
        }
        if (review.stage() != ReviewStage.JUDGING) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "judge decision is allowed only in JUDGING stage");
        }
        if (submission.metadata().expectedVersion() != review.version()) {
            throw new ReviewDomainException(ReviewErrorCode.VERSION_CONFLICT,
                    "expectedVersion does not match aggregate version");
        }
        DebateTopic topic = debateStore.findTopic(review.id(), submission.topicId())
                .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                        "judge topic does not belong to this review"));
        if (!topic.status().isTerminal()) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "judge topic must be resolved or escalated first");
        }
        validateTopicClaims(topic, submission.allReferencedClaimIds());
        JudgeDecision decision = new JudgeDecision(topic.id(), submission.proposedGateResult(),
                submission.publicReasonSummary(), submission.acceptedClaimIds(), submission.rejectedClaimIds(), Instant.now());
debateStore.saveJudgeDecision(review.id(), decision);
        review.recordCommand(submission.metadata(), topic.id().value().toString());
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ai.cc.chongming.review.domain.event.ReviewEventType.JUDGEMENT_SUBMITTED,
                RoleType.JUDGE,
                null,
                topic.id(),
                null,
                null,
                null,
                80,
                java.util.Map.of("result", decision.result().name())));
        return new JudgeResult(decision, false);
    }

    /** Produces and stores a non-final AI Gate draft from all review facts. */
    public GateDecision draftGate(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.stage() != ReviewStage.JUDGING && review.stage() != ReviewStage.WAITING_HUMAN) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "gate draft requires JUDGING or WAITING_HUMAN stage");
        }
        GateDecision existing = debateStore.findGateDraft(review.id()).orElse(null);
        if (existing != null) {
            return existing;
        }
        List<DebateTopic> topics = debateStore.findTopics(review.id());
        List<JudgeDecision> decisions = topics.stream()
                .map(topic -> debateStore.findJudgeDecision(review.id(), topic.id()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        if (decisions.size() != topics.size()) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "every terminal debate topic requires a Judge decision before Gate drafting");
        }
        GateDecision draft = gatePolicy.draft(review.id(), debateStore.findClaims(review.id()), decisions);
        debateStore.saveGateDraft(draft);
        boolean humanReviewRequired = draft.result() == GateResult.HUMAN_REQUIRED && review.stage() == ReviewStage.JUDGING;
        if (humanReviewRequired) {
            review.transitionTo(new ReviewStateMachine(), ReviewStage.WAITING_HUMAN);
        }
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ai.cc.chongming.review.domain.event.ReviewEventType.GATE_DRAFTED,
                RoleType.JUDGE,
                null,
                null,
                null,
                null,
                null,
                90,
                java.util.Map.of("result", draft.result().name(), "status", draft.status().name())));
        if (humanReviewRequired) {
            eventPublisher.publish(ReviewEventDrafts.completedCommand(
                    review,
                    ai.cc.chongming.review.domain.event.ReviewEventType.HUMAN_REVIEW_REQUIRED,
                    RoleType.JUDGE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    90,
                    java.util.Map.of("reason", draft.publicReasonSummary())));
        }
        return draft;
    }

    private void validateTopicClaims(DebateTopic topic, List<ClaimId> claimIds) {
        if (!topic.claimIds().containsAll(new LinkedHashSet<>(claimIds))) {
            throw new ReviewDomainException(ReviewErrorCode.TARGET_CLAIM_REQUIRED,
                    "judge may reference only claims belonging to its topic");
        }
    }

    /** @author wangli */
    public record JudgeSubmission(
            ReviewCommandMetadata metadata,
            TopicId topicId,
            GateResult proposedGateResult,
            String publicReasonSummary,
            List<ClaimId> acceptedClaimIds,
            List<ClaimId> rejectedClaimIds) {

        public JudgeSubmission {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(topicId, "topicId must not be null");
            if (proposedGateResult == null || proposedGateResult == GateResult.PASS || proposedGateResult == GateResult.OVERRIDE) {
                throw new IllegalArgumentException("judge may propose only an AI gate result");
            }
            if (publicReasonSummary == null || publicReasonSummary.isBlank()) {
                throw new IllegalArgumentException("publicReasonSummary must not be blank");
            }
            acceptedClaimIds = acceptedClaimIds == null ? List.of() : List.copyOf(acceptedClaimIds);
            rejectedClaimIds = rejectedClaimIds == null ? List.of() : List.copyOf(rejectedClaimIds);
        }

        public List<ClaimId> allReferencedClaimIds() {
            java.util.LinkedHashSet<ClaimId> values = new java.util.LinkedHashSet<>(acceptedClaimIds);
            values.addAll(rejectedClaimIds);
            return List.copyOf(values);
        }
    }

    /** @author wangli */
    public record JudgeResult(JudgeDecision decision, boolean replayed) {
    }
}
