package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-010#1.5] Applies bounded, reference-safe debate turns while the review aggregate owns idempotency and versions.
 *
 * @author wangli
 */
@Service
public class DebateService {

    private final ReviewDebateStore debateStore;
    private final EvidenceLedgerService evidenceLedgerService;
    private final DebateStateMachine debateStateMachine;
    private final ReviewProtocolGuard protocolGuard;
    private final ReviewEventPublisher eventPublisher;

    public DebateService(
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            DebateStateMachine debateStateMachine) {
        this(debateStore, evidenceLedgerService, debateStateMachine, new ReviewProtocolGuard(), ReviewEventPublisher.noop());
    }

    @Autowired
    public DebateService(
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            DebateStateMachine debateStateMachine,
            ReviewProtocolGuard protocolGuard,
            ReviewEventPublisher eventPublisher) {
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.evidenceLedgerService = Objects.requireNonNull(evidenceLedgerService, "evidenceLedgerService must not be null");
        this.debateStateMachine = Objects.requireNonNull(debateStateMachine, "debateStateMachine must not be null");
        this.protocolGuard = Objects.requireNonNull(protocolGuard, "protocolGuard must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /** Opens a topic from at least two existing Claims in the conflict-detection stage. */
    public TopicResult openTopic(Review review, DebateToolCommands.OpenTopic command) {
        requireReview(review, command.metadata());
        if (command.actorRole() != RoleType.DIRECTOR) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE, "only director may open a debate topic");
        }
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            DebateTopic topic = debateStore.findTopic(review.id(), new TopicId(UUID.fromString(existing)))
                    .orElseThrow(() -> new IllegalStateException("topic idempotency reference cannot be resolved"));
            return new TopicResult(topic, true);
        }
        requireVersionAndStage(review, command.metadata(), ReviewStage.CONFLICT_DETECTION);
        if (!protocolGuard.validateDebateStart(review.roleActivations()).isValid()) {
            throw new ReviewDomainException(ReviewErrorCode.CORE_ROLE_INITIAL_REVIEW_REQUIRED,
                    "all core roles must complete independent initial review before a debate topic opens");
        }
        List<Claim> claims = command.claimIds().stream().map(claimId -> debateStore.findClaim(review.id(), claimId)
                .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                        "topic claim does not belong to this review"))).toList();
        if (claims.stream().map(Claim::subjectKey).anyMatch(subject -> !subject.equalsIgnoreCase(command.subjectKey()))) {
            throw new ReviewDomainException(ReviewErrorCode.DUPLICATE_SUBMISSION,
                    "all topic claims must use the requested subjectKey");
        }
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), review.id(), command.subjectKey(), command.claimIds());
        debateStore.saveTopic(topic);
review.recordCommand(command.metadata(), topic.id().value().toString());
        review.transitionTo(new ai.cc.chongming.review.domain.protocol.ReviewStateMachine(), ReviewStage.DEBATE_ROUND_1);
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ai.cc.chongming.review.domain.event.ReviewEventType.DEBATE_TOPIC_OPENED,
                RoleType.DIRECTOR,
                null,
                topic.id(),
                null,
                null,
                1,
                60,
                Map.of("subjectKey", topic.subjectKey())));
        return new TopicResult(topic, false);
    }

    /** Stores a directed challenge against a Claim that is part of the selected topic. */
    public TurnResult submitChallenge(Review review, DebateToolCommands.Challenge command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TurnResult(requireTurn(review.id(), existing), true);
        }
        requireVersionAndRound(review, command.metadata(), command.round());
        Claim target = requireClaimInTopic(review.id(), topic, command.targetClaimId());
        requireActiveRole(review, command.actorRole());
        if (target.roleType() != command.targetRole() || command.actorRole() == command.targetRole()) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "challenge target role must own the target claim and differ from actor");
        }
        rejectRepeatedRoundOneChallenge(topic, command);
        DebateTurn turn = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), command.round(), command.actorRole(),
                command.targetRole(), DebateTurnType.CHALLENGE, command.targetClaimId(), null, command.publicContent(),
                validateEvidence(review.id(), command.evidenceIds()), null, null, Instant.now());
        topic.addChallenge(debateStateMachine, turn);
        debateStore.saveTurn(review.id(), turn);
        review.recordCommand(command.metadata(), turn.turnId().value().toString());
        publishTurn(review, turn);
        return new TurnResult(turn, false);
    }

    /** Stores a rebuttal that references an existing challenge/rebuttal in the same topic and round. */
    public TurnResult submitRebuttal(Review review, DebateToolCommands.Rebuttal command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TurnResult(requireTurn(review.id(), existing), true);
        }
        requireVersionAndRound(review, command.metadata(), command.round());
        DebateTurn target = debateStore.findTurn(review.id(), command.targetTurnId())
                .filter(turn -> turn.topicId().equals(topic.id()) && turn.round() == command.round())
                .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.TARGET_TURN_REQUIRED,
                        "rebuttal target turn must belong to this topic and round"));
        requireActiveRole(review, command.actorRole());
        if (target.actorRole() != command.targetRole() || command.actorRole() == command.targetRole()) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "rebuttal target role must own target turn and differ from actor");
        }
        DebateTurn turn = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), command.round(), command.actorRole(),
                command.targetRole(), DebateTurnType.REBUTTAL, null, command.targetTurnId(), command.publicContent(),
                validateEvidence(review.id(), command.evidenceIds()), null, null, Instant.now());
        topic.addRebuttal(debateStateMachine, turn);
        debateStore.saveTurn(review.id(), turn);
        review.recordCommand(command.metadata(), turn.turnId().value().toString());
        publishTurn(review, turn);
        return new TurnResult(turn, false);
    }

    /** Records a non-destructive position change; the source Claim remains immutable. */
    public TurnResult changePosition(Review review, DebateToolCommands.PositionChange command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TurnResult(requireTurn(review.id(), existing), true);
        }
        requireVersionAndRound(review, command.metadata(), command.round());
        Claim claim = requireClaimInTopic(review.id(), topic, command.targetClaimId());
        requireActiveRole(review, command.actorRole());
        if (claim.roleType() != command.actorRole()) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "only the owner role may change a claim position");
        }
        if (claim.position() == command.stanceAfter()) {
            throw new ReviewDomainException(ReviewErrorCode.DUPLICATE_SUBMISSION,
                    "position change must differ from the immutable source Claim position");
        }
        DebateTurn turn = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), command.round(), command.actorRole(),
                null, DebateTurnType.POSITION_CHANGE, command.targetClaimId(), null, command.publicContent(),
                validateEvidence(review.id(), command.evidenceIds()), claim.position(), command.stanceAfter(), Instant.now());
        debateStore.saveTurn(review.id(), turn);
        review.recordCommand(command.metadata(), turn.turnId().value().toString());
        publishTurn(review, turn);
        return new TurnResult(turn, false);
    }

    /**
     * Records a directed request for additional evidence. The request is immutable and does not fabricate Evidence.
     */
    public TurnResult requestAdditionalEvidence(Review review, DebateToolCommands.EvidenceRequest command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TurnResult(requireTurn(review.id(), existing), true);
        }
        requireVersionAndRound(review, command.metadata(), command.round());
        Claim target = requireClaimInTopic(review.id(), topic, command.targetClaimId());
        requireActiveRole(review, command.actorRole());
        if (target.roleType() != command.targetRole() || command.actorRole() == command.targetRole()) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "evidence request target role must own the target claim and differ from actor");
        }
        DebateTurn turn = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), command.round(), command.actorRole(),
                command.targetRole(), DebateTurnType.EVIDENCE_REQUEST, command.targetClaimId(), null,
                command.publicContent(), List.of(), null, null, Instant.now());
        debateStore.saveTurn(review.id(), turn);
        review.recordCommand(command.metadata(), turn.turnId().value().toString());
        publishTurn(review, turn);
        return new TurnResult(turn, false);
    }

    /** Advances the review only after round one; the two-round bound remains enforced by DebateStateMachine. */
    public void beginSecondRound(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        validateBeginSecondRound(review);
        review.transitionTo(new ai.cc.chongming.review.domain.protocol.ReviewStateMachine(), ReviewStage.DEBATE_ROUND_2);
    }

    /** Validates that the second round can begin without changing aggregate state. */
    public void validateBeginSecondRound(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.stage() != ReviewStage.DEBATE_ROUND_1) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "second debate round can begin only after round one");
        }
    }

    /** Enters judging only when every opened topic reached a terminal resolution or escalation. */
    public void beginJudging(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        validateBeginJudging(review);
        review.transitionTo(new ai.cc.chongming.review.domain.protocol.ReviewStateMachine(), ReviewStage.JUDGING);
    }

    /** Validates that judging can begin without changing aggregate state. */
    public void validateBeginJudging(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.stage() != ReviewStage.DEBATE_ROUND_2) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "judging can begin only after the second debate round");
        }
        if (debateStore.findTopics(review.id()).stream().anyMatch(topic -> !topic.status().isTerminal())) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "all debate topics must be terminal before judging");
        }
    }

    /**
     * Preserves the two-round state-machine audit trail when the completed initial review has no
     * conflicting Claim positions, then hands the review to the Judge for an AI Gate draft.
     */
    public void skipDebateWhenNoConflicts(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        if (review.stage() != ReviewStage.CONFLICT_DETECTION) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "debate can be skipped only during conflict detection");
        }
        if (!protocolGuard.validateDebateStart(review.roleActivations()).isValid()) {
            throw new ReviewDomainException(ReviewErrorCode.CORE_ROLE_INITIAL_REVIEW_REQUIRED,
                    "all core roles must complete independent initial review before debate can be skipped");
        }
        if (!debateStore.findTopics(review.id()).isEmpty() || hasConflictingClaimPositions(review)) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "debate can be skipped only when no conflicting Claim positions remain");
        }
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        review.transitionTo(stateMachine, ReviewStage.DEBATE_ROUND_1);
        review.transitionTo(stateMachine, ReviewStage.DEBATE_ROUND_2);
        review.transitionTo(stateMachine, ReviewStage.JUDGING);
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ai.cc.chongming.review.domain.event.ReviewEventType.DEBATE_SKIPPED,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                80,
                Map.of("reason", "NO_CONFLICTING_CLAIM_POSITIONS")));
    }

    /** Closes a topic with a public resolution or escalation reason. */
    public TopicResult closeTopic(Review review, DebateToolCommands.CloseTopic command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TopicResult(topic, true);
        }
        if (review.stage() != ReviewStage.DEBATE_ROUND_1 && review.stage() != ReviewStage.DEBATE_ROUND_2) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "a debate topic can be closed only during an active debate round");
        }
        requireVersion(review, command.metadata());
topic.close(debateStateMachine, command.status(), command.publicResolution(), Instant.now());
        review.recordCommand(command.metadata(), topic.id().value().toString());
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ai.cc.chongming.review.domain.event.ReviewEventType.DEBATE_TOPIC_CLOSED,
                RoleType.DIRECTOR,
                null,
                topic.id(),
                null,
                null,
                topic.currentRound(),
                review.stage() == ReviewStage.DEBATE_ROUND_1 ? 60 : 70,
                Map.of("status", topic.status().name())));
        return new TopicResult(topic, false);
    }

    private void publishTurn(Review review, DebateTurn turn) {
        ai.cc.chongming.review.domain.event.ReviewEventType type = switch (turn.turnType()) {
            case CHALLENGE -> ai.cc.chongming.review.domain.event.ReviewEventType.CHALLENGE_SUBMITTED;
            case REBUTTAL -> ai.cc.chongming.review.domain.event.ReviewEventType.REBUTTAL_SUBMITTED;
            case POSITION_CHANGE -> ai.cc.chongming.review.domain.event.ReviewEventType.POSITION_CHANGED;
            case EVIDENCE_REQUEST -> ai.cc.chongming.review.domain.event.ReviewEventType.EVIDENCE_REQUESTED;
            default -> throw new IllegalStateException("unsupported persisted debate turn type: " + turn.turnType());
        };
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                type,
                turn.actorRole(),
                turn.targetRole(),
                turn.topicId(),
                turn.targetClaimId(),
                turn.turnId(),
                turn.round(),
                review.stage() == ReviewStage.DEBATE_ROUND_1 ? 60 : 70,
                Map.of("turnType", turn.turnType().name())));
    }
    private DebateTopic requireTopic(Review review, ReviewCommandMetadata metadata, TopicId topicId) {
        requireReview(review, metadata);
        return debateStore.findTopic(review.id(), topicId)
                .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                        "topic does not belong to this review"));
    }

    private boolean hasConflictingClaimPositions(Review review) {
        return debateStore.findClaims(review.id()).stream()
                .filter(claim -> claim.status() != ClaimStatus.WITHDRAWN)
                .collect(java.util.stream.Collectors.groupingBy(
                        claim -> claim.subjectKey().trim().toLowerCase(java.util.Locale.ROOT),
                        java.util.stream.Collectors.mapping(Claim::position, java.util.stream.Collectors.toSet())))
                .values().stream()
                .anyMatch(positions -> positions.size() > 1);
    }

    private Claim requireClaimInTopic(ReviewId reviewId, DebateTopic topic, ClaimId claimId) {
        if (!topic.claimIds().contains(claimId)) {
            throw new ReviewDomainException(ReviewErrorCode.TARGET_CLAIM_REQUIRED,
                    "target claim is not part of the debate topic");
        }
        return debateStore.findClaim(reviewId, claimId)
                .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.TARGET_CLAIM_REQUIRED,
                        "target claim cannot be resolved"));
    }

    private DebateTurn requireTurn(ReviewId reviewId, String persistedId) {
        return debateStore.findTurn(reviewId, new TurnId(UUID.fromString(persistedId)))
                .orElseThrow(() -> new IllegalStateException("turn idempotency reference cannot be resolved"));
    }

    private List<EvidenceId> validateEvidence(ReviewId reviewId, List<EvidenceId> evidenceIds) {
        LinkedHashSet<EvidenceId> distinctIds = new LinkedHashSet<>(evidenceIds);
        if (distinctIds.isEmpty()) {
            return List.of();
        }
        if (evidenceLedgerService.findByIds(reviewId, distinctIds).size() != distinctIds.size()) {
            throw new ReviewDomainException(ReviewErrorCode.INVALID_EVIDENCE,
                    "turn contains evidence that does not belong to this review");
        }
        return List.copyOf(distinctIds);
    }

    private void requireReview(Review review, ReviewCommandMetadata metadata) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (!review.id().equals(metadata.reviewId())) {
            throw new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                    "debate command reviewId does not match aggregate");
        }
    }

    private void requireVersionAndStage(Review review, ReviewCommandMetadata metadata, ReviewStage stage) {
        if (review.stage() != stage) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "debate command is not allowed in the current review stage");
        }
        requireVersion(review, metadata);
    }

    private void requireVersionAndRound(Review review, ReviewCommandMetadata metadata, int round) {
        debateStateMachine.validateRound(round);
        ReviewStage requiredStage = round == 1 ? ReviewStage.DEBATE_ROUND_1 : ReviewStage.DEBATE_ROUND_2;
        if (review.stage() != requiredStage) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "debate round does not match current review stage");
        }
        requireVersion(review, metadata);
    }

    private void rejectRepeatedRoundOneChallenge(DebateTopic topic, DebateToolCommands.Challenge command) {
        if (command.round() != 2) {
            return;
        }
        String content = command.publicContent().trim().replaceAll("\\s+", " ");
        boolean repeated = topic.turns().stream().anyMatch(turn -> turn.round() == 1
                && turn.turnType() == DebateTurnType.CHALLENGE
                && turn.actorRole() == command.actorRole()
                && turn.targetClaimId().equals(command.targetClaimId())
                && turn.publicContent().trim().replaceAll("\\s+", " ").equalsIgnoreCase(content));
        if (repeated) {
            throw new ReviewDomainException(ReviewErrorCode.DUPLICATE_SUBMISSION,
                    "round two challenge must add a new argument or evidence instead of repeating round one");
        }
    }

    private void requireVersion(Review review, ReviewCommandMetadata metadata) {
        if (metadata.expectedVersion() != review.version()) {
            throw new ReviewDomainException(ReviewErrorCode.VERSION_CONFLICT,
                    "expectedVersion does not match aggregate version");
        }
    }

    private void requireActiveRole(Review review, RoleType roleType) {
        boolean active = review.roleActivations().stream().anyMatch(activation -> activation.roleType() == roleType);
        if (!active || roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "only an activated non-director review role may submit a debate turn");
        }
    }

    /** @author wangli */
    public record TopicResult(DebateTopic topic, boolean replayed) {
    }

    /** @author wangli */
    public record TurnResult(DebateTurn turn, boolean replayed) {
    }
}
