package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-003#1.2,#1.4] Captures an evidence-linked disagreement and its bounded turns.
 *
 * @author wangli
 */
public final class DebateTopic {

    private final TopicId id;
    private final ReviewId reviewId;
    private final String subjectKey;
    private final String publicTitle;
    private List<ClaimId> claimIds;
    private final List<DebateTurn> turns = new ArrayList<>();
    private DebateTopicStatus status;
    private int currentRound;
    private String resolution;
    private Instant closedAt;

    public DebateTopic(TopicId id, ReviewId reviewId, String subjectKey, List<ClaimId> claimIds) {
        this(id, reviewId, subjectKey, claimIds, null);
    }

    /**
     * [AIREVIEW-PLAN-044#1] Creates a topic with an optional display-only Chinese public title;
     * subjectKey remains the matching key. The title is nullable and never enters matching or dedup.
     */
    public DebateTopic(TopicId id, ReviewId reviewId, String subjectKey, List<ClaimId> claimIds, String publicTitle) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new IllegalArgumentException("subjectKey must not be blank");
        }
        this.subjectKey = subjectKey;
        this.publicTitle = publicTitle;
        // [AIREVIEW-PLAN-024#方案4] claimIds may be empty when the topic is opened from a purely
        // Assessment-borne contradiction; Claim-backed topics keep their previous contract.
        this.claimIds = List.copyOf(claimIds);
        this.status = DebateTopicStatus.OPEN;
    }

    /**
     * Rebuilds an immutable-snapshot topic from durable storage, bypassing the live state machine.
     * Used by the persistence layer to restore topics after a restart.
     */
    public static DebateTopic restore(
            TopicId id,
            ReviewId reviewId,
            String subjectKey,
            List<ClaimId> claimIds,
            DebateTopicStatus status,
            int currentRound,
            List<DebateTurn> turns,
            String resolution,
            Instant closedAt) {
        return restore(id, reviewId, subjectKey, claimIds, null, status, currentRound, turns, resolution, closedAt);
    }

    /**
     * [AIREVIEW-PLAN-044#1] Restore overload that also carries the optional display-only Chinese
     * public title; the legacy signature delegates with a null title.
     */
    public static DebateTopic restore(
            TopicId id,
            ReviewId reviewId,
            String subjectKey,
            List<ClaimId> claimIds,
            String publicTitle,
            DebateTopicStatus status,
            int currentRound,
            List<DebateTurn> turns,
            String resolution,
            Instant closedAt) {
        DebateTopic topic = new DebateTopic(id, reviewId, subjectKey, claimIds, publicTitle);
        topic.status = Objects.requireNonNull(status, "status must not be null");
        topic.currentRound = currentRound;
        topic.turns.addAll(List.copyOf(turns));
        topic.resolution = resolution;
        topic.closedAt = closedAt;
        return topic;
    }

    public TopicId id() {
        return id;
    }

    public ReviewId reviewId() {
        return reviewId;
    }

    public String subjectKey() {
        return subjectKey;
    }

    /**
     * [AIREVIEW-PLAN-044#1] Display-only Chinese public title; null when the Director did not
     * provide one (read models and the frontend fall back to subjectKey).
     */
    public String publicTitle() {
        return publicTitle;
    }

    public List<ClaimId> claimIds() {
        return claimIds;
    }

    /**
     * [AIREVIEW-PLAN-040#1] Idempotently attaches a defence-side Claim to this topic without touching
     * the turn state machine or round counters: an already-mounted id is a no-op, otherwise the id is
     * appended after the original members.
     */
    public void attachClaim(ClaimId claimId) {
        Objects.requireNonNull(claimId, "claimId must not be null");
        if (claimIds.contains(claimId)) {
            return;
        }
        List<ClaimId> appended = new ArrayList<>(claimIds);
        appended.add(claimId);
        claimIds = List.copyOf(appended);
    }

    public DebateTopicStatus status() {
        return status;
    }

    public int currentRound() {
        return currentRound;
    }

    public List<DebateTurn> turns() {
        return List.copyOf(turns);
    }

    /**
     * [AIREVIEW-PLAN-047#1] Topic-level second round: the topic's own currentRound moves to 2
     * without touching the review's global stage. The two-round cap is hard: only a topic that
     * completed round one can start round two, and a topic already on round two can never start a
     * third.
     */
    public void beginSecondRound() {
        if (currentRound != 1) {
            throw new ai.cc.chongming.review.domain.exception.ReviewDomainException(
                    ai.cc.chongming.review.domain.exception.ReviewErrorCode.DEBATE_ROUND_EXCEEDED,
                    "a topic may begin its second round only once, after completing round one");
        }
        currentRound = 2;
    }

    public void addChallenge(DebateStateMachine stateMachine, DebateTurn turn) {
        stateMachine.validateChallenge(turn.round(), turn.targetClaimId());
        status = stateMachine.transition(status, DebateTopicStatus.CHALLENGED);
        currentRound = turn.round();
        turns.add(turn);
    }

    public void addRebuttal(DebateStateMachine stateMachine, DebateTurn turn) {
        stateMachine.validateRebuttal(turn.round(), turn.targetTurnId());
        status = stateMachine.transition(status, DebateTopicStatus.REBUTTED);
        currentRound = turn.round();
        turns.add(turn);
    }

    public void close(DebateStateMachine stateMachine, DebateTopicStatus terminalStatus, String publicResolution, Instant at) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("terminalStatus must be RESOLVED or ESCALATED");
        }
        status = stateMachine.transition(status, terminalStatus);
        if (publicResolution == null || publicResolution.isBlank()) {
            throw new IllegalArgumentException("publicResolution must not be blank");
        }
        resolution = publicResolution;
        closedAt = Objects.requireNonNull(at, "at must not be null");
    }

    public String resolution() {
        return resolution;
    }

    public Instant closedAt() {
        return closedAt;
    }
}

