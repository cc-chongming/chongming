package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventCategory;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.EvidenceBlock;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.JudgeDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.repository.ReviewAssessmentStore;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.repository.ReviewRepositories;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * [AIREVIEW-PLAN-010#1.3][AIREVIEW-PLAN-011#1.3][AIREVIEW-PLAN-012#1.8][AIREVIEW-PLAN-023#5]
 * Builds public review read models from append-only events and batch domain stores.
 *
 * @author zyj
 */
@Service
public class ReviewQueryService {

    private static final int EVENT_STORE_BATCH_SIZE = 10_000;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            // [AIREVIEW-PLAN-025] Display times are always China time, independent of server TZ.
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final ReviewEventStore eventStore;
    private final ReviewDebateStore debateStore;
    private final EvidenceLedgerService evidenceLedgerService;
    private final HumanGateDecisionStore humanGateDecisionStore;
    private final ReviewRegistry reviewRegistry;
    private final ReviewRepositories reviewRepositories;
    private final ContextScoutConclusionStore contextScoutConclusionStore;
    private final ReviewAssessmentStore assessmentStore;
    private final RolePackRegistry rolePackRegistry;

    ReviewQueryService(
            ReviewEventStore eventStore,
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            HumanGateDecisionStore humanGateDecisionStore,
            ReviewRegistry reviewRegistry) {
        this(eventStore, debateStore, evidenceLedgerService, humanGateDecisionStore, reviewRegistry,
                (ReviewRepositories) null, null, null, null);
    }

    ReviewQueryService(
            ReviewEventStore eventStore,
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            HumanGateDecisionStore humanGateDecisionStore,
            ReviewRegistry reviewRegistry,
            ContextScoutConclusionStore contextScoutConclusionStore) {
        this(eventStore, debateStore, evidenceLedgerService, humanGateDecisionStore, reviewRegistry,
                (ReviewRepositories) null, contextScoutConclusionStore, null, null);
    }

    public ReviewQueryService(
            ReviewEventStore eventStore,
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            HumanGateDecisionStore humanGateDecisionStore,
            ReviewRegistry reviewRegistry,
            ObjectProvider<ReviewRepositories> reviewRepositoriesProvider) {
        this(eventStore, debateStore, evidenceLedgerService, humanGateDecisionStore, reviewRegistry,
                reviewRepositoriesProvider.getIfAvailable(), null, null, null);
    }

    public ReviewQueryService(
            ReviewEventStore eventStore,
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            HumanGateDecisionStore humanGateDecisionStore,
            ReviewRegistry reviewRegistry,
            ObjectProvider<ReviewRepositories> reviewRepositoriesProvider,
            ContextScoutConclusionStore contextScoutConclusionStore) {
        this(eventStore, debateStore, evidenceLedgerService, humanGateDecisionStore, reviewRegistry,
                reviewRepositoriesProvider.getIfAvailable(), contextScoutConclusionStore, null, null);
    }

    @Autowired
    public ReviewQueryService(
            ReviewEventStore eventStore,
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            HumanGateDecisionStore humanGateDecisionStore,
            ReviewRegistry reviewRegistry,
            ObjectProvider<ReviewRepositories> reviewRepositoriesProvider,
            ContextScoutConclusionStore contextScoutConclusionStore,
            ObjectProvider<ReviewAssessmentStore> assessmentStoreProvider,
            ObjectProvider<RolePackRegistry> rolePackRegistryProvider) {
        this(eventStore, debateStore, evidenceLedgerService, humanGateDecisionStore, reviewRegistry,
                reviewRepositoriesProvider.getIfAvailable(), contextScoutConclusionStore,
                assessmentStoreProvider.getIfAvailable(), rolePackRegistryProvider.getIfAvailable());
    }

    private ReviewQueryService(
            ReviewEventStore eventStore,
            ReviewDebateStore debateStore,
            EvidenceLedgerService evidenceLedgerService,
            HumanGateDecisionStore humanGateDecisionStore,
            ReviewRegistry reviewRegistry,
            ReviewRepositories reviewRepositories,
            ContextScoutConclusionStore contextScoutConclusionStore,
            ReviewAssessmentStore assessmentStore,
            RolePackRegistry rolePackRegistry) {
        this.eventStore = eventStore;
        this.debateStore = debateStore;
        this.evidenceLedgerService = evidenceLedgerService;
        this.humanGateDecisionStore = humanGateDecisionStore;
        this.reviewRegistry = reviewRegistry;
        this.reviewRepositories = reviewRepositories;
        this.contextScoutConclusionStore = contextScoutConclusionStore;
        this.assessmentStore = assessmentStore;
        this.rolePackRegistry = rolePackRegistry;
    }

    @Transactional(readOnly = true)
    public Optional<ReviewSummary> findSummary(ReviewId reviewId) {
        Optional<ReviewEvent> latestEvent = eventStore.findLatest(reviewId);
        Optional<GateDecision> gate = debateStore.findGateDraft(reviewId);
        Optional<HumanGateDecision> humanGate = humanGateDecisionStore.findLatest(reviewId);
        ReviewEvent event = latestEvent.orElse(null);
        // [AIREVIEW-PLAN-012#1.8] After a restart the process-local registry is empty. Fall back to the
        // durable review projection (role_activation persists INITIAL_REVIEW_COMPLETED) so the
        // independent-review N/4 counter and review version survive without the aggregate in memory.
        Review review = reviewRegistry.find(reviewId)
                .orElseGet(() -> reviewRepositories == null
                        ? null
                        : reviewRepositories.findReview(reviewId).orElse(null));
        Long reviewVersion = review == null ? null : review.version();
        int currentAttempt = review == null ? (event == null ? 0 : event.attemptNo()) : review.attemptNo();
        Optional<ContextScoutConclusion> conclusion = currentAttempt < 1 || contextScoutConclusionStore == null
                ? Optional.empty()
                : contextScoutConclusionStore.find(reviewId, currentAttempt);
        if (latestEvent.isEmpty() && gate.isEmpty() && humanGate.isEmpty() && conclusion.isEmpty()) {
            return Optional.empty();
        }
        GateView gateView = humanGate.map(this::toGateView)
                .orElseGet(() -> gate.map(this::toGateView).orElse(null));
        ContextScoutView contextScout = currentAttempt < 1
                ? null
                : conclusion.map(this::toContextScoutView)
                        .orElseGet(() -> eventStore.findLatestByTypeAndAttempt(
                                        reviewId, ReviewEventType.CONTEXT_SCOUT_COMPLETED, currentAttempt)
                                .map(this::toLegacyContextScoutView)
                                .orElseGet(() -> eventStore.findLatestByTypeAndAttempt(
                                                reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED, currentAttempt)
                                        .map(this::toContextScoutView)
                                        .orElse(null)));
        return Optional.of(new ReviewSummary(
                reviewId.value(),
                event == null ? null : event.attemptNo(),
                event == null ? null : event.stage().name(),
                event == null ? null : event.progress(),
                event == null ? 0L : event.sequence(),
                reviewVersion,
                event == null ? null : format(event.occurredAt()),
                gateView,
                contextScout,
                review == null ? List.of() : review.roleActivations().stream().map(this::toRoleActivationView).toList()));
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

    /**
     * Exposes every persisted public claim regardless of debate topic membership, so role cards keep
     * showing submitted viewpoints before conflict detection binds claims to topics.
     */
    public List<ClaimView> findClaims(ReviewId reviewId) {
        return debateStore.findClaims(reviewId).stream()
                .sorted(Comparator.comparing(claim -> claim.claimId().value()))
                .map(this::toClaimView)
                .toList();
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] Projects the five-status assessments of the review's current
     * attempt, sorted deterministically by role and checkpointKey, together with the server-side
     * coverage summary derived from the core RolePack required checkpoints.
     */
    @Transactional(readOnly = true)
    public AssessmentsView findAssessments(ReviewId reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Integer attempt = currentAttempt(reviewId);
        if (attempt == null || attempt < 1) {
            return new AssessmentsView(null, emptyCoverage(), List.of());
        }
        return new AssessmentsView(attempt, findAssessmentCoverage(reviewId, attempt),
                findAssessmentViews(reviewId, attempt));
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] One batch store read of all assessments of one attempt.
     */
    @Transactional(readOnly = true)
    public List<AssessmentView> findAssessmentViews(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (assessmentStore == null) {
            return List.of();
        }
        return assessmentStore.findByReview(reviewId, attemptNo).stream()
                .map(this::toAssessmentView)
                .toList();
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] Coverage counters computed only from persisted assessments and the
     * RolePack required checkpoint contract; models never hand-write these numbers.
     */
    @Transactional(readOnly = true)
    public AssessmentCoverageView findAssessmentCoverage(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        List<ReviewAssessment> assessments = assessmentStore == null
                ? List.of()
                : assessmentStore.findByReview(reviewId, attemptNo);
        Set<RequiredCheckpointSlot> required = requiredCheckpointSlots();
        Set<RequiredCheckpointSlot> covered = new HashSet<>();
        Map<AssessmentStatus, Long> counts = new EnumMap<>(AssessmentStatus.class);
        for (AssessmentStatus status : AssessmentStatus.values()) {
            counts.put(status, 0L);
        }
        for (ReviewAssessment assessment : assessments) {
            counts.merge(assessment.status(), 1L, Long::sum);
            covered.add(new RequiredCheckpointSlot(assessment.roleType(), assessment.checkpointKey()));
        }
        Set<String> uncovered = new TreeSet<>();
        int coveredRequired = 0;
        for (RequiredCheckpointSlot slot : required) {
            if (covered.contains(slot)) {
                coveredRequired++;
            } else {
                uncovered.add(slot.roleType().name() + ":" + slot.checkpointKey());
            }
        }
        return new AssessmentCoverageView(
                required.size(),
                coveredRequired,
                counts.get(AssessmentStatus.CONFIRMED).intValue(),
                counts.get(AssessmentStatus.PARTIAL).intValue(),
                counts.get(AssessmentStatus.GAP).intValue(),
                counts.get(AssessmentStatus.UNKNOWN).intValue(),
                counts.get(AssessmentStatus.NOT_APPLICABLE).intValue(),
                List.copyOf(uncovered));
    }

    private Set<RequiredCheckpointSlot> requiredCheckpointSlots() {
        if (rolePackRegistry == null) {
            return Set.of();
        }
        Set<RequiredCheckpointSlot> slots = new HashSet<>();
        for (RolePack rolePack : rolePackRegistry.all()) {
            if (!rolePack.roleType().isCore()) {
                continue;
            }
            for (RolePack.Checkpoint checkpoint : rolePack.checklist()) {
                if (checkpoint.required() && checkpoint.hasStableKey()) {
                    slots.add(new RequiredCheckpointSlot(rolePack.roleType(), checkpoint.checkpointKey()));
                }
            }
        }
        return slots;
    }

    private Integer currentAttempt(ReviewId reviewId) {
        Review review = reviewRegistry.find(reviewId)
                .orElseGet(() -> reviewRepositories == null
                        ? null
                        : reviewRepositories.findReview(reviewId).orElse(null));
        if (review != null) {
            return review.attemptNo();
        }
        return eventStore.findLatest(reviewId).map(ReviewEvent::attemptNo).orElse(null);
    }

    private AssessmentCoverageView emptyCoverage() {
        return new AssessmentCoverageView(0, 0, 0, 0, 0, 0, 0, List.of());
    }

    private AssessmentView toAssessmentView(ReviewAssessment assessment) {
        return new AssessmentView(
                assessment.roleType().name(),
                assessment.checkpointKey(),
                assessment.status().name(),
                assessment.summary(),
                assessment.reasonSummary(),
                assessment.evidenceIds().stream().map(EvidenceId::value).toList(),
                format(assessment.createdAt()));
    }

    private record RequiredCheckpointSlot(RoleType roleType, String checkpointKey) {
    }

    private DebateView toDebateView(
            DebateTopic topic,
            Map<ClaimId, Claim> claimsById,
            Map<TopicId, List<DebateTurn>> turnsByTopic,
            Map<TopicId, JudgeDecision> decisionsByTopic) {
        List<ClaimView> claims = new ArrayList<>(topic.claimIds().stream()
                .map(claimsById::get)
                .filter(java.util.Objects::nonNull)
                .map(this::toClaimView)
                .toList());
        // [AIREVIEW-PLAN-040#1] Heal legacy topics whose DEFENSE support claims were accepted but never
        // mounted: append every non-withdrawn same-subject claim that is not an original member,
        // keeping the original membership order first. No migration is needed for existing stores.
        for (Claim claim : claimsById.values()) {
            if (topic.claimIds().contains(claim.claimId())
                    || claim.status() == ClaimStatus.WITHDRAWN
                    || !topic.subjectKey().trim().equalsIgnoreCase(claim.subjectKey().trim())) {
                continue;
            }
            claims.add(toClaimView(claim));
        }
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
                Optional.ofNullable(decisionsByTopic.get(topic.id())).map(this::toJudgeView).orElse(null),
                topic.publicTitle());
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

    private GateView toGateView(HumanGateDecision decision) {
        return new GateView(
                decision.result().name(),
                "FINAL",
                "HUMAN",
                decision.reason(),
                format(decision.decidedAt()));
    }

    private ContextScoutView toContextScoutView(ReviewEvent event) {
        return new ContextScoutView(
                event.payload().getOrDefault("status", "DEGRADED"),
                event.payload().getOrDefault("reasonCode", "CONTEXT_SCOUT_UNAVAILABLE"),
                event.payload().getOrDefault(
                        "publicSummary",
                        "Context Scout 未能完成项目上下文预处理，Director 将继续评审。"),
                format(event.occurredAt()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                null,
                false);
    }

    private ContextScoutView toContextScoutView(ContextScoutConclusion conclusion) {
        return new ContextScoutView(
                "COMPLETED",
                null,
                conclusion.summary(),
                format(conclusion.createdAt()),
                conclusion.schemaVersion(),
                conclusion.moduleRoots(),
                conclusion.entryPoints(),
                conclusion.constraints(),
                conclusion.risks(),
                conclusion.evidencePaths(),
                conclusion.roleScopes(),
                conclusion.rawPublicResult(),
                false);
    }

    private ContextScoutView toLegacyContextScoutView(ReviewEvent event) {
        return new ContextScoutView(
                event.payload().getOrDefault("status", "COMPLETED"),
                null,
                event.payload().getOrDefault("publicSummary", "Context Scout 已完成上下文收集。"),
                format(event.occurredAt()),
                parseInteger(event.payload().get("schemaVersion")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                null,
                true);
    }

    private RoleActivationView toRoleActivationView(ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation activation) {
        return new RoleActivationView(
                activation.roleType().name(), activation.agentLabel(), activation.initialReviewCompleted());
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

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
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
            Long reviewVersion,
            String occurredAt,
            GateView gate,
            ContextScoutView contextScout,
            List<RoleActivationView> activatedRoles) {
        public ReviewSummary {
            activatedRoles = List.copyOf(activatedRoles);
        }

        public ReviewSummary(
                UUID reviewId,
                Integer attempt,
                String stage,
                Integer progress,
                long lastSequence,
                Long reviewVersion,
                String occurredAt,
                GateView gate,
                ContextScoutView contextScout) {
            this(
                    reviewId,
                    attempt,
                    stage,
                    progress,
                    lastSequence,
                    reviewVersion,
                    occurredAt,
                    gate,
                    contextScout,
                    List.of());
        }
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
            JudgeView judgement,
            // [AIREVIEW-PLAN-044#1] Display-only Chinese public title; null falls back to subjectKey.
            String title) {
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
     * [AIREVIEW-PLAN-024#方案5] One five-status checkpoint assessment projected for public reads.
     *
     * @author wangli
     */
    public record AssessmentView(
            String role,
            String checkpointKey,
            String status,
            String summary,
            String reasonSummary,
            List<UUID> evidenceIds,
            String createdAt) {
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] Server-side coverage summary; {@code uncoveredCheckpoints} lists
     * still-missing required slots as {@code ROLE:checkpointKey} in stable order.
     *
     * @author wangli
     */
    public record AssessmentCoverageView(
            int required,
            int covered,
            int confirmed,
            int partial,
            int gap,
            int unknown,
            int notApplicable,
            List<String> uncoveredCheckpoints) {
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] Assessment query response consumed by the frontend workbench.
     *
     * @author wangli
     */
    public record AssessmentsView(
            Integer attempt,
            AssessmentCoverageView coverage,
            List<AssessmentView> assessments) {
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
     * [AIREVIEW-PLAN-023#5]
     *
     * @author zyj
     */
    public record ContextScoutView(
            String status,
            String reasonCode,
            String publicSummary,
            String occurredAt,
            Integer schemaVersion,
            List<String> moduleRoots,
            List<String> entryPoints,
            List<String> constraints,
            List<String> risks,
            List<String> evidencePaths,
            Map<String, List<String>> roleScopes,
            String rawPublicResult,
            boolean legacy) {

        public ContextScoutView {
            moduleRoots = List.copyOf(moduleRoots);
            entryPoints = List.copyOf(entryPoints);
            constraints = List.copyOf(constraints);
            risks = List.copyOf(risks);
            evidencePaths = List.copyOf(evidencePaths);
            roleScopes = Map.copyOf(roleScopes);
        }

        public ContextScoutView(String status, String reasonCode, String publicSummary, String occurredAt) {
            this(
                    status,
                    reasonCode,
                    publicSummary,
                    occurredAt,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    null,
                    false);
        }
    }

    /**
     * @author zyj
     */
    public record RoleActivationView(String role, String agentLabel, boolean initialReviewCompleted) {
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
