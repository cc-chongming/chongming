package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventCategory;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.EvidenceBlock;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.JudgeDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * [AIREVIEW-PLAN-010#1.3] Builds public review read models from append-only events and batch domain stores.
 *
 * @author wangli
 */
@Service
public class ReviewQueryService {

    private static final int EVENT_STORE_BATCH_SIZE = 10_000;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ReviewEventStore eventStore;
    private final ReviewDebateStore debateStore;
    private final EvidenceLedgerService evidenceLedgerService;

    public ReviewQueryService(
            ReviewEventStore eventStore,
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService) {
        this.eventStore = eventStore;
        this.debateStore = debateStore;
        this.evidenceLedgerService = evidenceLedgerService;
    }

    public Optional<ReviewSummary> findSummary(ReviewId reviewId) {
        Optional<ReviewEvent> latestEvent = eventStore.findLatest(reviewId);
        Optional<GateDecision> gate = debateStore.findGateDraft(reviewId);
        if (latestEvent.isEmpty() && gate.isEmpty()) {
            return Optional.empty();
        }
        ReviewEvent event = latestEvent.orElse(null);
        return Optional.of(new ReviewSummary(
                reviewId.value(),
                event == null ? null : event.attemptNo(),
                event == null ? null : event.stage().name(),
                event == null ? null : event.progress(),
                event == null ? 0L : event.sequence(),
                event == null ? null : format(event.occurredAt()),
                gate.map(this::toGateView).orElse(null)));
    }

    /**
     * Pages only PLAN category events. The cursor remains the review-global event sequence.
     */
    public EventPage findPlans(ReviewId reviewId, long afterSequence, int limit) {
        validatePage(afterSequence, limit);
        List<EventView> plans = new ArrayList<>();
        long scanCursor = afterSequence;
        boolean exhausted = false;
        while (plans.size() < limit && !exhausted) {
            List<ReviewEvent> events = eventStore.findAfter(reviewId, scanCursor, EVENT_STORE_BATCH_SIZE);
            if (events.isEmpty()) {
                exhausted = true;
                break;
            }
            for (ReviewEvent event : events) {
                scanCursor = event.sequence();
                if (event.category() == ReviewEventCategory.PLAN) {
                    plans.add(toEventView(event));
                    if (plans.size() == limit) {
                        break;
                    }
                }
            }
            exhausted = events.size() < EVENT_STORE_BATCH_SIZE;
        }
        Long nextAfterSequence = plans.isEmpty() || exhausted ? null : scanCursor;
        return new EventPage(List.copyOf(plans), nextAfterSequence);
    }

    /**
     * Uses one bulk read for claims, topics, turns and judge decisions before composing topic views.
     */
    public List<DebateView> findDebates(ReviewId reviewId) {
        Map<ClaimId, Claim> claimsById = debateStore.findClaims(reviewId).stream()
                .collect(Collectors.toMap(Claim::claimId, claim -> claim, (left, right) -> left, LinkedHashMap::new));
        Map<TopicId, List<DebateTurn>> turnsByTopic = debateStore.findTurns(reviewId).stream()
                .collect(Collectors.groupingBy(DebateTurn::topicId, LinkedHashMap::new, Collectors.toList()));
        Map<TopicId, JudgeDecision> decisionsByTopic = debateStore.findJudgeDecisions(reviewId);

        return debateStore.findTopics(reviewId).stream()
                .sorted(Comparator.comparing(topic -> topic.id().value()))
                .map(topic -> toDebateView(topic, claimsById, turnsByTopic, decisionsByTopic))
                .toList();
    }

    public Optional<EvidenceView> findEvidence(ReviewId reviewId, EvidenceId evidenceId) {
        return Optional.ofNullable(evidenceLedgerService.findByIds(reviewId, Set.of(evidenceId)).get(evidenceId))
                .map(this::toEvidenceView);
    }

    private DebateView toDebateView(
            DebateTopic topic,
            Map<ClaimId, Claim> claimsById,
            Map<TopicId, List<DebateTurn>> turnsByTopic,
            Map<TopicId, JudgeDecision> decisionsByTopic) {
        List<ClaimView> claims = topic.claimIds().stream()
                .map(claimsById::get)
                .filter(java.util.Objects::nonNull)
                .map(this::toClaimView)
                .toList();
        List<TurnView> turns = turnsByTopic.getOrDefault(topic.id(), List.of()).stream()
                .sorted(Comparator.comparingInt(DebateTurn::round).thenComparing(turn -> turn.turnId().value()))
                .map(this::toTurnView)
                .toList();
        return new DebateView(
                topic.id().value(),
                topic.subjectKey(),
                topic.claimIds().stream().map(ClaimId::value).toList(),
                topic.status().name(),
                topic.currentRound(),
                topic.resolution(),
                topic.closedAt() == null ? null : format(topic.closedAt()),
                claims,
                turns,
                Optional.ofNullable(decisionsByTopic.get(topic.id())).map(this::toJudgeView).orElse(null));
    }

    private EventView toEventView(ReviewEvent event) {
        return new EventView(
                event.eventId(),
                event.sequence(),
                event.reviewId().value(),
                event.attemptNo(),
                event.type().name(),
                event.category().name(),
                event.stage().name(),
                event.actorRole() == null ? null : event.actorRole().name(),
                event.targetRole() == null ? null : event.targetRole().name(),
                event.topicId() == null ? null : event.topicId().value(),
                event.claimId() == null ? null : event.claimId().value(),
                event.turnId() == null ? null : event.turnId().value(),
                event.round(),
                event.progress(),
                format(event.occurredAt()),
                event.payloadVersion(),
                event.payload());
    }

    private ClaimView toClaimView(Claim claim) {
        return new ClaimView(
                claim.claimId().value(),
                claim.roleType().name(),
                claim.subjectKey(),
                claim.severity().name(),
                claim.position().name(),
                claim.statement(),
                claim.reasonSummary(),
                claim.status().name(),
                claim.evidenceReferences().stream().map(reference -> reference.evidenceId().value()).toList());
    }

    private TurnView toTurnView(DebateTurn turn) {
        return new TurnView(
                turn.turnId().value(),
                turn.round(),
                turn.actorRole().name(),
                turn.targetRole() == null ? null : turn.targetRole().name(),
                turn.turnType().name(),
                turn.targetClaimId() == null ? null : turn.targetClaimId().value(),
                turn.targetTurnId() == null ? null : turn.targetTurnId().value(),
                turn.publicContent(),
                turn.evidenceIds().stream().map(EvidenceId::value).toList(),
                turn.stanceBefore() == null ? null : turn.stanceBefore().name(),
                turn.stanceAfter() == null ? null : turn.stanceAfter().name(),
                format(turn.createdAt()));
    }

    private JudgeView toJudgeView(JudgeDecision decision) {
        return new JudgeView(
                decision.result().name(),
                decision.publicReasonSummary(),
                decision.acceptedClaimIds().stream().map(ClaimId::value).toList(),
                decision.rejectedClaimIds().stream().map(ClaimId::value).toList(),
                format(decision.createdAt()));
    }

    private GateView toGateView(GateDecision decision) {
        return new GateView(
                decision.result().name(),
                decision.status().name(),
                decision.actor().name(),
                decision.publicReasonSummary(),
                format(decision.decidedAt()));
    }

    private EvidenceView toEvidenceView(EvidenceBlock evidence) {
        return new EvidenceView(
                evidence.evidenceId().value(),
                evidence.repositorySnapshotId(),
                evidence.repoRevision(),
                evidence.snapshotRelativePath(),
                evidence.lineNumber(),
                evidence.excerpt(),
                evidence.excerptHash(),
                evidence.fileHash(),
                format(evidence.createdAt()));
    }

    private void validatePage(long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("afterSequence must be non-negative and limit must be between 1 and 500");
        }
    }

    private String format(Instant instant) {
        return TIME_FORMATTER.format(instant);
    }

    /**
     * @author wangli
     */
    public record ReviewSummary(
            UUID reviewId,
            Integer attempt,
            String stage,
            Integer progress,
            long lastSequence,
            String occurredAt,
            GateView gate) {
    }

    /**
     * @author wangli
     */
    public record EventPage(List<EventView> items, Long nextAfterSequence) {
    }

    /**
     * @author wangli
     */
    public record EventView(
            UUID eventId,
            long sequence,
            UUID reviewId,
            int attempt,
            String type,
            String category,
            String stage,
            String actorRole,
            String targetRole,
            UUID topicId,
            UUID claimId,
            UUID turnId,
            Integer round,
            Integer progress,
            String occurredAt,
            int payloadVersion,
            Map<String, String> payload) {
    }

    /**
     * @author wangli
     */
    public record DebateView(
            UUID topicId,
            String subjectKey,
            List<UUID> claimIds,
            String status,
            int currentRound,
            String resolution,
            String closedAt,
            List<ClaimView> claims,
            List<TurnView> turns,
            JudgeView judgement) {
    }

    /**
     * @author wangli
     */
    public record ClaimView(
            UUID claimId,
            String role,
            String subjectKey,
            String severity,
            String position,
            String statement,
            String reasonSummary,
            String status,
            List<UUID> evidenceIds) {
    }

    /**
     * @author wangli
     */
    public record TurnView(
            UUID turnId,
            int round,
            String actorRole,
            String targetRole,
            String type,
            UUID targetClaimId,
            UUID targetTurnId,
            String content,
            List<UUID> evidenceIds,
            String stanceBefore,
            String stanceAfter,
            String createdAt) {
    }

    /**
     * @author wangli
     */
    public record JudgeView(
            String result,
            String reasonSummary,
            List<UUID> acceptedClaimIds,
            List<UUID> rejectedClaimIds,
            String createdAt) {
    }

    /**
     * @author wangli
     */
    public record GateView(String result, String status, String actor, String reasonSummary, String decidedAt) {
    }

    /**
     * @author wangli
     */
    public record EvidenceView(
            UUID evidenceId,
            UUID repositorySnapshotId,
            String repoRevision,
            String snapshotRelativePath,
            int lineNumber,
            String excerpt,
            String excerptHash,
            String fileHash,
            String createdAt) {
    }
}
