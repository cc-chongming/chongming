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
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import ai.cc.chongming.review.infrastructure.audit.InMemoryReviewConflictAuditStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-010#1.5][AIREVIEW-PLAN-024#方案4][AIREVIEW-PLAN-047#1] Applies bounded,
 * reference-safe debate turns
 * while the review aggregate owns idempotency and versions. Conflict candidates come from the
 * deterministic {@link ConflictDetectionService}; topic registration is batch, idempotent and
 * atomic, and rebuttals are bound to the challenged role by identity.
 *
 * @author zyj
 */
@Service
public class DebateService {

    private final ReviewDebateStore debateStore;
    private final EvidenceLedgerService evidenceLedgerService;
    private final DebateStateMachine debateStateMachine;
    private final ReviewProtocolGuard protocolGuard;
    private final ReviewEventPublisher eventPublisher;
    private final ConflictDetectionService conflictDetectionService;

    /**
     * [AIREVIEW-PLAN-024#方案4] Monotonic turn clock: wall-clock instants may repeat within one
     * test/jiffy, and equal createdAt values would shuffle turn order by random turn id, breaking
     * "answered after the evidence request" reasoning. Later turns always carry a later instant.
     */
    private static final AtomicReference<Instant> LAST_TURN_INSTANT = new AtomicReference<>(Instant.EPOCH);

    private static Instant nextTurnInstant() {
        return LAST_TURN_INSTANT.updateAndGet(previous -> {
            Instant now = Instant.now();
            return now.isAfter(previous) ? now : previous.plusNanos(1);
        });
    }

    public DebateService(
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            DebateStateMachine debateStateMachine) {
        this(debateStore, evidenceLedgerService, debateStateMachine, new ReviewProtocolGuard(), ReviewEventPublisher.noop());
    }

    public DebateService(
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            DebateStateMachine debateStateMachine,
            ReviewProtocolGuard protocolGuard,
            ReviewEventPublisher eventPublisher) {
        this(debateStore, evidenceLedgerService, debateStateMachine, protocolGuard, eventPublisher,
                new ConflictDetectionService(
                        new InMemoryReviewAssessmentStore(), debateStore, new InMemoryReviewConflictAuditStore()));
    }

    @Autowired
    public DebateService(
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            DebateStateMachine debateStateMachine,
            ReviewProtocolGuard protocolGuard,
            ReviewEventPublisher eventPublisher,
            ConflictDetectionService conflictDetectionService) {
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.evidenceLedgerService = Objects.requireNonNull(evidenceLedgerService, "evidenceLedgerService must not be null");
        this.debateStateMachine = Objects.requireNonNull(debateStateMachine, "debateStateMachine must not be null");
        this.protocolGuard = Objects.requireNonNull(protocolGuard, "protocolGuard must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.conflictDetectionService = Objects.requireNonNull(conflictDetectionService, "conflictDetectionService must not be null");
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Registers every Director-chosen conflict candidate in one batch.
     * All proposals are fully validated and deduplicated before anything is persisted, all topics
     * are then saved atomically inside this one operation, and only afterwards does the review
     * migrate from CONFLICT_DETECTION to the single DEBATE phase exactly once. Replaying the same
     * idempotency key returns the already registered topics without touching the stage again.
     */
    @Transactional
    public RegisterTopicsResult registerTopics(Review review, DebateToolCommands.RegisterTopics command) {
        Objects.requireNonNull(review, "review must not be null");
        synchronized (review) {
            return registerTopicsLocked(review, command);
        }
    }

    private RegisterTopicsResult registerTopicsLocked(
            Review review, DebateToolCommands.RegisterTopics command) {
        requireReview(review, command.metadata());
        if (command.actorRole() != RoleType.DIRECTOR) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "only director may register debate topics");
        }
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            List<TopicResult> replayed = Arrays.stream(existing.split(","))
                    .filter(id -> !id.isBlank())
                    .map(id -> debateStore.findTopic(review.id(), new TopicId(UUID.fromString(id)))
                            .orElseThrow(() -> new IllegalStateException("topic idempotency reference cannot be resolved")))
                    .map(topic -> new TopicResult(topic, true))
                    .toList();
            return new RegisterTopicsResult(replayed, true);
        }
        requireVersionAndStage(review, command.metadata(), ReviewStage.CONFLICT_DETECTION);
        if (!protocolGuard.validateDebateStart(review.roleActivations()).isValid()) {
            throw new ReviewDomainException(ReviewErrorCode.CORE_ROLE_INITIAL_REVIEW_REQUIRED,
                    "all core roles must complete independent initial review before debate topics register");
        }
        // Complete validation and idempotency-key dedup BEFORE any persistence: a single invalid
        // proposal must reject the whole batch, and duplicate subjects register once.
        LinkedHashMap<String, DebateToolCommands.TopicProposal> deduplicated = new LinkedHashMap<>();
        for (DebateToolCommands.TopicProposal proposal : command.proposals()) {
            String normalized = proposal.subjectKey().trim().toLowerCase(Locale.ROOT);
            if (deduplicated.putIfAbsent(normalized, proposal) != null) {
                continue;
            }
            for (ClaimId claimId : proposal.claimIds()) {
                debateStore.findClaim(review.id(), claimId)
                        .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                                "topic claim does not belong to this review"));
            }
        }
        List<TopicResult> registered = new ArrayList<>();
        List<String> persistedIds = new ArrayList<>();
        List<DebateTopic> topicsToSave = new ArrayList<>();
        for (DebateToolCommands.TopicProposal proposal : deduplicated.values()) {
            DebateTopic topic = new DebateTopic(
                    new TopicId(UUID.randomUUID()), review.id(), proposal.subjectKey(), proposal.claimIds(),
                    normalizePublicTitle(proposal.publicTitle()));
            topicsToSave.add(topic);
            registered.add(new TopicResult(topic, false));
            persistedIds.add(topic.id().value().toString());
        }
        conflictDetectionService.recordTopicRegistration(review,
                deduplicated.values().stream().map(DebateToolCommands.TopicProposal::subjectKey).toList());
        debateStore.saveTopics(topicsToSave);
        review.recordCommand(command.metadata(), String.join(",", persistedIds));
        // The stage migrates exactly once, after every topic of the batch is persisted. Rounds are
        // topic-level from here on (AIREVIEW-PLAN-047#1).
        review.transitionTo(new ai.cc.chongming.review.domain.protocol.ReviewStateMachine(), ReviewStage.DEBATE);
        for (TopicResult result : registered) {
            eventPublisher.publish(ReviewEventDrafts.completedCommand(
                    review,
                    ai.cc.chongming.review.domain.event.ReviewEventType.DEBATE_TOPIC_OPENED,
                    RoleType.DIRECTOR,
                    null,
                    result.topic().id(),
                    null,
                    null,
                    1,
                    60,
                    Map.of("subjectKey", result.topic().subjectKey())));
        }
        return new RegisterTopicsResult(registered, false);
    }

    /** Stores a directed challenge against a Claim that is part of the selected topic. */
    public TurnResult submitChallenge(Review review, DebateToolCommands.Challenge command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TurnResult(requireTurn(review.id(), existing), true);
        }
        requireVersionAndRound(review, topic, command.metadata(), command.round());
        Claim target = requireClaimInTopic(review.id(), topic, command.targetClaimId());
        requireActiveRole(review, command.actorRole());
        if (target.roleType() != command.targetRole() || command.actorRole() == command.targetRole()) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "challenge target role must own the target claim and differ from actor");
        }
        rejectRepeatedRoundOneChallenge(topic, command);
        DebateTurn turn = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), command.round(), command.actorRole(),
                command.targetRole(), DebateTurnType.CHALLENGE, command.targetClaimId(), null, command.publicContent(),
                validateEvidence(review.id(), command.evidenceIds()), null, null, nextTurnInstant());
        topic.addChallenge(debateStateMachine, turn);
        debateStore.saveTopic(topic);
        debateStore.saveTurn(review.id(), turn);
        review.recordCommand(command.metadata(), turn.turnId().value().toString());
        publishTurn(review, turn);
        return new TurnResult(turn, false);
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Stores a rebuttal bound to the challenged role by identity. The
     * target turn keeps its original round, so a round-two answer to a round-one challenge carries
     * the true target turn id instead of being rewritten to the current round.
     */
    public TurnResult submitRebuttal(Review review, DebateToolCommands.Rebuttal command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TurnResult(requireTurn(review.id(), existing), true);
        }
        requireVersionAndRound(review, topic, command.metadata(), command.round());
        // The target turn must belong to this topic (any of its rounds); the action must match a
        // challenge, and only the challenged role itself may answer it.
        DebateTurn target = debateStore.findTurn(review.id(), command.targetTurnId())
                .filter(turn -> turn.topicId().equals(topic.id()))
                .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.TARGET_TURN_REQUIRED,
                        "rebuttal target turn must belong to this topic"));
        requireActiveRole(review, command.actorRole());
        if (target.turnType() != DebateTurnType.CHALLENGE
                || target.targetRole() == null
                || command.actorRole() != target.targetRole()
                || command.targetRole() != target.actorRole()) {
            throw new ReviewDomainException(ReviewErrorCode.DISPATCH_ACTOR_MISMATCH,
                    "only " + (target.targetRole() == null ? "the challenged role" : target.targetRole())
                            + " may rebut challenge " + target.turnId().value()
                            + "; rebuttal actor/target roles and topic, review, attempt identity must match the challenge");
        }
        DebateTurn turn = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), command.round(), command.actorRole(),
                command.targetRole(), DebateTurnType.REBUTTAL, null, command.targetTurnId(), command.publicContent(),
                validateEvidence(review.id(), command.evidenceIds()), null, null, nextTurnInstant());
        topic.addRebuttal(debateStateMachine, turn);
        debateStore.saveTopic(topic);
        debateStore.saveTurn(review.id(), turn);
        review.recordCommand(command.metadata(), turn.turnId().value().toString());
        publishTurn(review, turn);
        return new TurnResult(turn, false);
    }

    /** [AIREVIEW-PLAN-044#1] Trim the optional display title and normalize whitespace-only values to null. */
    private static String normalizePublicTitle(String publicTitle) {
        if (publicTitle == null) {
            return null;
        }
        String normalized = publicTitle.trim();
        return normalized.isBlank() ? null : normalized;
    }

    /** Records a non-destructive position change; the source Claim remains immutable. */
    public TurnResult changePosition(Review review, DebateToolCommands.PositionChange command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TurnResult(requireTurn(review.id(), existing), true);
        }
        requireVersionAndRound(review, topic, command.metadata(), command.round());
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
                validateEvidence(review.id(), command.evidenceIds()), claim.position(), command.stanceAfter(), nextTurnInstant());
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
        requireVersionAndRound(review, topic, command.metadata(), command.round());
        Claim target = requireClaimInTopic(review.id(), topic, command.targetClaimId());
        requireActiveRole(review, command.actorRole());
        if (target.roleType() != command.targetRole() || command.actorRole() == command.targetRole()) {
            throw new ReviewDomainException(ReviewErrorCode.UNAUTHORIZED_ROLE,
                    "evidence request target role must own the target claim and differ from actor");
        }
        DebateTurn turn = new DebateTurn(new TurnId(UUID.randomUUID()), topic.id(), command.round(), command.actorRole(),
                command.targetRole(), DebateTurnType.EVIDENCE_REQUEST, command.targetClaimId(), null,
                command.publicContent(), List.of(), null, null, nextTurnInstant());
        debateStore.saveTurn(review.id(), turn);
        review.recordCommand(command.metadata(), turn.turnId().value().toString());
        publishTurn(review, turn);
        return new TurnResult(turn, false);
    }

    /**
     * [AIREVIEW-PLAN-047#1] Begins the bounded second round for ONE topic without moving the whole
     * review: the review stays in the single DEBATE phase while every other topic keeps its own
     * round. The topic must still be non-terminal and must have completed round one
     * (currentRound == 1). A topic that already reached round two is treated as an idempotent
     * replay, so the two-round cap is hard. The DEBATE_ROUND_2_STARTED fact now names the topicId
     * so the live page and the Director wake interpret it per topic.
     */
    @Transactional
    public TopicRoundResult beginTopicSecondRound(
            Review review, ReviewCommandMetadata metadata, TopicId topicId) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(topicId, "topicId must not be null");
        synchronized (review) {
            DebateTopic topic = requireTopic(review, metadata, topicId);
            String existing = review.commandResults().get(metadata.idempotencyKey());
            if (existing != null || topic.currentRound() == 2) {
                // [AIREVIEW-PLAN-047#1] Replay semantics: the same command key, or an already
                // round-two topic under a fresh key, never starts a third round.
                return new TopicRoundResult(topic, true);
            }
            requireDebateStage(review, "a second debate round can begin only during an active debate");
            if (topic.status().isTerminal()) {
                throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                        "a second debate round cannot begin for a terminal topic; converge to judging instead");
            }
            if (topic.currentRound() != 1) {
                throw new ReviewDomainException(ReviewErrorCode.DEBATE_ROUND_EXCEEDED,
                        "a second round may begin only for a topic that completed round one");
            }
            // [AIREVIEW-PLAN-024#方案4] No empty rounds at topic level: without a valid open action
            // (evidence owed, an unanswered challenge, or unclarified positions) the topic converges
            // to judging instead of running an empty second round. Evidence requests persist only in
            // the store, so the topic is rehydrated with its store turns before the check.
            if (!debateStateMachine.requiresSecondRoundAction(topicWithStoreTurns(review, topic))) {
                throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                        "no valid open debate action remains on this topic; converge to judging instead of running an empty second round");
            }
            topic.beginSecondRound();
            debateStore.saveTopic(topic);
            review.recordCommand(metadata, topic.id().value().toString());
            // The topic-level round transition is a public fact: without it the live page keeps
            // painting round one until the first round-two turn commits (AIREVIEW-PLAN-047#1).
            eventPublisher.publish(ReviewEventDrafts.completedCommand(
                    review,
                    ai.cc.chongming.review.domain.event.ReviewEventType.DEBATE_ROUND_2_STARTED,
                    RoleType.DIRECTOR,
                    null,
                    topic.id(),
                    null,
                    null,
                    2,
                    65,
                    Map.of("topicId", topic.id().value().toString())));
            return new TopicRoundResult(topic, false);
        }
    }

    /**
     * [AIREVIEW-PLAN-047#1] Rebuilds one topic snapshot with the authoritative store turns so the
     * topic-level round check sees evidence requests, which never mutate the topic aggregate itself.
     */
    private DebateTopic topicWithStoreTurns(Review review, DebateTopic topic) {
        return DebateTopic.restore(topic.id(), topic.reviewId(), topic.subjectKey(), topic.claimIds(),
                topic.publicTitle(), topic.status(), topic.currentRound(),
                debateStore.findTurns(review.id(), topic.id()), topic.resolution(), topic.closedAt());
    }

    /** Enters judging only when every opened topic reached a terminal resolution or escalation. */
    public void beginJudging(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        validateBeginJudging(review);
        review.transitionTo(new ai.cc.chongming.review.domain.protocol.ReviewStateMachine(), ReviewStage.JUDGING);
        // Public transition fact so the live page leaves the debate phase immediately instead of
        // staying on "round two in progress" until the first judging event arrives.
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ai.cc.chongming.review.domain.event.ReviewEventType.JUDGING_STARTED,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                75,
                Map.of()));
    }

    /** Validates that judging can begin without changing aggregate state. */
    public void validateBeginJudging(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        // [AIREVIEW-PLAN-024#方案4][AIREVIEW-PLAN-047#1] Early convergence: judging may start from
        // the single DEBATE phase (or a legacy round stage) when every topic is already terminal,
        // skipping a would-be empty second round.
        requireDebateStage(review, "judging can begin only during an active debate round");
        if (debateStore.findTopics(review.id()).stream().anyMatch(topic -> !topic.status().isTerminal())) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "all debate topics must be terminal before judging");
        }
    }

    /**
     * Preserves the two-round state-machine audit trail when the deterministic conflict detection
     * finds no candidate, then hands the review to the Judge for an AI Gate draft.
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
        // [AIREVIEW-PLAN-024#方案4] The production flow consumes the deterministic detector; a single
        // GAP/UNKNOWN risk never blocks skipping, only a real contradictory candidate does.
        ConflictDetectionService.Outcome detection = conflictDetectionService.detect(review);
        if (!debateStore.findTopics(review.id()).isEmpty() || !detection.result().candidates().isEmpty()) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "debate can be skipped only when no deterministic conflict candidate remains");
        }
        // [AIREVIEW-PLAN-024#方案4 收口 / 2026-08-19 修订] The Director prompt already promises to skip
        // only when no persisted Claim has an OPPOSE position, but the server did not enforce it:
        // a prompt-violating Director could silently discard lone opposition. Align the guard with
        // the prompt so any unwithdrawn OPPOSE Claim keeps the review in debate.
        boolean unwithdrawnOppose = debateStore.findClaims(review.id()).stream()
                .anyMatch(claim -> claim.position() == ClaimPosition.OPPOSE
                        && claim.status() != ClaimStatus.WITHDRAWN);
        if (unwithdrawnOppose) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "debate can be skipped only when no unwithdrawn OPPOSE claim remains");
        }
        conflictDetectionService.recordTopicRegistration(review, List.of());
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        // [AIREVIEW-PLAN-047#1] Enter the single DEBATE phase and immediately converge to judging,
        // keeping the audit trail one hop from conflict detection instead of two round stages.
        review.transitionTo(stateMachine, ReviewStage.DEBATE);
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
                Map.of("reason", "NO_CONFLICT_CANDIDATES")));
    }

    /** Closes a topic with a public resolution or escalation reason. */
    public TopicResult closeTopic(Review review, DebateToolCommands.CloseTopic command) {
        DebateTopic topic = requireTopic(review, command.metadata(), command.topicId());
        String existing = review.commandResults().get(command.metadata().idempotencyKey());
        if (existing != null) {
            return new TopicResult(topic, true);
        }
        requireDebateStage(review, "a debate topic can be closed only during an active debate round");
        requireVersion(review, command.metadata());
topic.close(debateStateMachine, command.status(), command.publicResolution(), Instant.now());
        debateStore.saveTopic(topic);
        review.recordCommand(command.metadata(), topic.id().value().toString());
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ai.cc.chongming.review.domain.event.ReviewEventType.DEBATE_TOPIC_CLOSED,
                RoleType.DIRECTOR,
                null,
                topic.id(),
                null,
                null,
                // A never-challenged topic keeps currentRound 0; the event round must reflect the
                // topic's own round instead so the draft stays valid (AIREVIEW-PLAN-047#1).
                topic.currentRound() == 2 ? 2 : 1,
                topic.currentRound() == 2 ? 70 : 60,
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
                // [AIREVIEW-PLAN-047#1] Progress follows the turn's own round (topic-level).
                turn.round() == 1 ? 60 : 70,
                Map.of("turnType", turn.turnType().name())));
    }
    private DebateTopic requireTopic(Review review, ReviewCommandMetadata metadata, TopicId topicId) {
        requireReview(review, metadata);
        return debateStore.findTopic(review.id(), topicId)
                .orElseThrow(() -> new ReviewDomainException(ReviewErrorCode.REVIEW_ID_MISMATCH,
                        "topic does not belong to this review"));
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

    /**
     * [AIREVIEW-PLAN-047#1] Turn gates are phase- and topic-level: the review must be in the single
     * DEBATE phase (legacy round stages tolerated for in-flight reviews) and the claimed round must
     * match the topic's own round. Round-two turns are accepted only after the topic itself began
     * its second round; round-one turns are accepted only before it did.
     */
    private void requireVersionAndRound(
            Review review, DebateTopic topic, ReviewCommandMetadata metadata, int round) {
        debateStateMachine.validateRound(round);
        requireDebateStage(review, "debate turns are allowed only during an active debate");
        if (round == 2 && topic.currentRound() != 2) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "round two turn requires the topic to have begun its second round");
        }
        if (round == 1 && topic.currentRound() == 2) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION,
                    "round one turn is no longer accepted after the topic began its second round");
        }
        requireVersion(review, metadata);
    }

    /**
     * [AIREVIEW-PLAN-047#1] Accepts the single DEBATE phase for new reviews and the legacy round
     * stages so paused in-flight reviews can still finish.
     */
    private void requireDebateStage(Review review, String message) {
        ReviewStage stage = review.stage();
        if (stage != ReviewStage.DEBATE
                && stage != ReviewStage.DEBATE_ROUND_1
                && stage != ReviewStage.DEBATE_ROUND_2) {
            throw new ReviewDomainException(ReviewErrorCode.ILLEGAL_STATE_TRANSITION, message);
        }
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

    /** @author zyj */
    public record TopicResult(DebateTopic topic, boolean replayed) {
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Batch registration output: every registered topic in input order and
     * the idempotent-replay flag.
     *
     * @author zyj
     */
    public record RegisterTopicsResult(List<TopicResult> topics, boolean replayed) {
        public RegisterTopicsResult {
            topics = List.copyOf(topics);
        }
    }

    /** @author zyj */
    public record TurnResult(DebateTurn turn, boolean replayed) {
    }

    /**
     * [AIREVIEW-PLAN-047#1] Topic-level second-round start output with an explicit
     * idempotent-replay indicator.
     *
     * @author zyj
     */
    public record TopicRoundResult(DebateTopic topic, boolean replayed) {
    }
}
