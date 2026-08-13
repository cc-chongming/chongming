package ai.cc.chongming.review.lifecycle;

import ai.cc.chongming.review.application.AssessmentService;
import ai.cc.chongming.review.application.ConflictDetectionService;
import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.HumanGateDecisionService;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.application.NotificationOutboxService;
import ai.cc.chongming.review.application.ReviewEventService;
import ai.cc.chongming.review.application.ReviewOrchestrationService;
import ai.cc.chongming.review.application.ReviewQueryService;
import ai.cc.chongming.review.application.ReviewReportService;
import ai.cc.chongming.review.config.NotificationOutboxProperties;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.gate.GatePolicy;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewConflictAudit;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.Permission;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import ai.cc.chongming.review.infrastructure.audit.InMemoryReviewConflictAuditStore;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.event.InMemoryReviewEventStore;
import ai.cc.chongming.review.infrastructure.human.InMemoryHumanGateDecisionStore;
import ai.cc.chongming.review.infrastructure.human.InMemoryHumanReviewItemStore;
import ai.cc.chongming.review.infrastructure.notification.InMemoryNotificationOutboxStore;
import ai.cc.chongming.review.infrastructure.report.InMemoryReviewReportStore;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [AIREVIEW-PLAN-024#方案7] Deterministic quality-convergence fixtures for complete positive
 * coverage, multi-topic directed debate, and UNKNOWN-to-human-to-notification lifecycle closure.
 *
 * @author zyj
 */
class ReviewQualityConvergenceIntegrationTests {

    private final ReviewStateMachine reviewStateMachine = new ReviewStateMachine();
    private final InMemoryReviewAssessmentStore assessmentStore = new InMemoryReviewAssessmentStore();
    private final InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
    private final ReviewEventService events = new ReviewEventService(new InMemoryReviewEventStore());
    private RolePackRegistry rolePacks;
    private AssessmentService assessments;
    private ConflictDetectionService conflicts;
    private DebateService debates;
    private JudgeService judge;

    @BeforeEach
    void setUp() {
        rolePacks = new RolePackRegistry(new PathMatchingResourcePatternResolver());
        assessments = new AssessmentService(assessmentStore, rolePacks);
        conflicts = new ConflictDetectionService(
                assessmentStore, debateStore, new InMemoryReviewConflictAuditStore());
        debates = new DebateService(
                debateStore,
                new EvidenceLedgerService(),
                new DebateStateMachine(),
                new ReviewProtocolGuard(),
                events,
                conflicts);
        judge = new JudgeService(debateStore, new GatePolicy(), events, assessmentStore, rolePacks);
    }

    @Test
    void allConfirmedCoverageReachesAiPassWithoutInventingConflict() {
        Review review = initialReview();
        submitAllRequired(review, ignored -> AssessmentStatus.CONFIRMED);
        review.transitionTo(reviewStateMachine, ReviewStage.CONFLICT_DETECTION);

        ConflictDetectionService.Outcome outcome = conflicts.detect(review);
        debates.skipDebateWhenNoConflicts(review);
        GateDecision gate = judge.draftGate(review);

        assertThat(outcome.result().candidates()).isEmpty();
        assertThat(outcome.gateRiskAssessments()).isEmpty();
        assertThat(debateStore.findTopics(review.id())).isEmpty();
        assertThat(gate.result()).isEqualTo(GateResult.AI_PASS);
        assertThat(gate.publicReasonSummary())
                .contains("required checkpoints fully covered")
                .contains("unknown=0")
                .contains("gap=0");
        assertThat(review.stage()).isEqualTo(ReviewStage.WAITING_HUMAN);
    }

    @Test
    void conflictingSubjectsRegisterMultipleTopicsAndConvergeThroughDirectedRebuttals() {
        Review review = initialReview();
        submitAllRequired(review, ignored -> AssessmentStatus.CONFIRMED);
        addConflictingFacts(review, "shared.consistency", RoleType.PRODUCT, RoleType.BACKEND);
        addConflictingFacts(review, "shared.ownership", RoleType.FRONTEND, RoleType.PROJECT);
        review.transitionTo(reviewStateMachine, ReviewStage.CONFLICT_DETECTION);

        ConflictDetectionService.Outcome outcome = conflicts.detect(review);
        List<DebateToolCommands.TopicProposal> proposals = outcome.result().candidates().stream()
                .map(candidate -> new DebateToolCommands.TopicProposal(candidate.subjectKey(), candidate.claimIds()))
                .toList();
        List<DebateTopic> topics = debates.registerTopics(review,
                        new DebateToolCommands.RegisterTopics(metadata(review, "register-all-conflicts"),
                                RoleType.DIRECTOR, proposals))
                .topics().stream()
                .map(DebateService.TopicResult::topic)
                .toList();
        for (DebateTopic topic : topics) {
            Claim oppose = debateStore.findClaims(review.id()).stream()
                    .filter(claim -> topic.claimIds().contains(claim.claimId()))
                    .filter(claim -> claim.position() == ClaimPosition.OPPOSE)
                    .findFirst()
                    .orElseThrow();
            Claim support = debateStore.findClaims(review.id()).stream()
                    .filter(claim -> topic.claimIds().contains(claim.claimId()))
                    .filter(claim -> claim.position() == ClaimPosition.SUPPORT)
                    .findFirst()
                    .orElseThrow();
            DebateService.TurnResult challenge = debates.submitChallenge(review,
                    new DebateToolCommands.Challenge(
                            metadata(review, "challenge-" + topic.subjectKey()),
                            support.roleType(), oppose.roleType(), topic.id(), 1, oppose.claimId(),
                            "说明该检查点的缺口处置。", List.of(), oppose.reasonSummary()));
            debates.submitRebuttal(review, new DebateToolCommands.Rebuttal(
                    metadata(review, "rebuttal-" + topic.subjectKey()),
                    oppose.roleType(), support.roleType(), topic.id(), 1, challenge.turn().turnId(),
                    "被挑战角色已明确回应并接受人工处置。", List.of()));
            debates.closeTopic(review, new DebateToolCommands.CloseTopic(
                    metadata(review, "close-" + topic.subjectKey()), topic.id(),
                    DebateTopicStatus.ESCALATED, "闭环到人工 Gate。"));
        }

        debates.beginJudging(review);
        for (DebateTopic topic : topics) {
            judge.submitJudgement(review, new JudgeService.JudgeSubmission(
                    metadata(review, "judge-" + topic.subjectKey()), topic.id(), GateResult.CONDITIONAL,
                    "主题已定向收敛，需人工确认处置条件。", topic.claimIds(), List.of()));
        }
        GateDecision gate = judge.draftGate(review);

        assertThat(outcome.result().candidates()).hasSize(2);
        assertThat(topics).hasSize(2);
        assertThat(conflicts.auditRecords(review.id(), review.attemptNo()))
                .filteredOn(record -> record.disposition() == ReviewConflictAudit.Disposition.REGISTERED)
                .hasSize(2);
        assertThat(debateStore.findTurns(review.id())).hasSize(4);
        assertThat(gate.result()).isEqualTo(GateResult.CONDITIONAL);
        assertThat(review.stage()).isEqualTo(ReviewStage.WAITING_HUMAN);
    }

    @Test
    void highRiskUnknownRequiresHumanReportNotificationAndCompletedStage() {
        Review review = initialReview();
        AtomicBoolean unknownSubmitted = new AtomicBoolean();
        submitAllRequired(review, slot -> slot.roleType() == RoleType.FRONTEND && !unknownSubmitted.getAndSet(true)
                ? AssessmentStatus.UNKNOWN
                : AssessmentStatus.CONFIRMED);
        review.transitionTo(reviewStateMachine, ReviewStage.CONFLICT_DETECTION);

        ConflictDetectionService.Outcome outcome = conflicts.detect(review);
        debates.skipDebateWhenNoConflicts(review);
        GateDecision aiDraft = judge.draftGate(review);

        assertThat(outcome.result().candidates()).isEmpty();
        assertThat(outcome.gateRiskAssessments()).hasSize(1);
        assertThat(aiDraft.result()).isEqualTo(GateResult.HUMAN_REQUIRED);
        assertThat(review.stage()).isEqualTo(ReviewStage.WAITING_HUMAN);

        InMemoryHumanGateDecisionStore decisionStore = new InMemoryHumanGateDecisionStore();
        HumanGateDecisionService humanGate = new HumanGateDecisionService(
                decisionStore,
                debateStore,
                () -> new ReviewerIdentity("reviewer-1", java.util.Set.of(Permission.REVIEW)),
                new ReviewProtocolGuard(),
                reviewStateMachine,
                events);
        HumanGateDecision finalDecision = humanGate.finalizeDecision(review,
                new HumanGateDecisionService.FinalDecisionCommand(
                        review.version(), GateResult.PASS, "人工已核实未知项并准予通过。", List.of(), null));

        InMemoryReviewRegistry registry = new InMemoryReviewRegistry();
        registry.register(review);
        ReviewReport report = report(review, decisionStore, registry);

        ReviewOrchestrationService orchestration = mock(ReviewOrchestrationService.class);
        when(orchestration.releaseRuntime(review.id(), review.attemptNo())).thenReturn(Mono.empty());
        NotificationOutboxService notifications = new NotificationOutboxService(
                new InMemoryNotificationOutboxStore(),
                decisionStore,
                registry,
                reviewStateMachine,
                events,
                new NotificationOutboxProperties(false, false, "learning-platform", "recipient-placeholder",
                        "MISSING_TEST_TOKEN", 3, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                Clock.fixed(Instant.parse("2026-08-11T03:30:00Z"), ZoneOffset.UTC),
                orchestration);
        notifications.enqueue(review, finalDecision);
        int delivered = notifications.dispatchDue(
                ignored -> new NotificationDeliveryReceipt("202", "a".repeat(64)), 10);

        assertThat(report.reportVersion()).isEqualTo(1L);
        assertThat(report.gateVersion()).isEqualTo(finalDecision.gateVersion());
        assertThat(delivered).isEqualTo(1);
        assertThat(notifications.findByReview(review.id()).getFirst().deliveryStatus())
                .isEqualTo(ai.cc.chongming.review.domain.model.NotificationOutboxEntry.DeliveryStatus.SENT);
        assertThat(review.stage()).isEqualTo(ReviewStage.COMPLETED);
        assertThat(events.replay(review.id(), 0L, 100).stream().map(event -> event.type()).toList())
                .containsSubsequence(
                        ReviewEventType.GATE_DRAFTED,
                        ReviewEventType.HUMAN_REVIEW_REQUIRED,
                        ReviewEventType.HUMAN_GATE_FINALIZED,
                        ReviewEventType.NOTIFICATION_SENT);
    }

    private ReviewReport report(
            Review review,
            InMemoryHumanGateDecisionStore decisionStore,
            InMemoryReviewRegistry registry) {
        ReviewQueryService query = mock(ReviewQueryService.class);
        when(query.findSummary(review.id())).thenReturn(java.util.Optional.of(new ReviewQueryService.ReviewSummary(
                review.id().value(), review.attemptNo(), review.stage().name(), 95, 0L, review.version(),
                "2026-08-11 11:30:00",
                new ReviewQueryService.GateView("PASS", "FINAL", "HUMAN", "人工已核实未知项。",
                        "2026-08-11 11:30:00"), null)));
        when(query.findPlans(review.id(), 0L, 500))
                .thenReturn(new ReviewQueryService.EventPage(List.of(), null));
        when(query.findDebates(review.id())).thenReturn(List.of());
        when(query.findClaims(review.id())).thenReturn(List.of());
        List<ReviewAssessment> stored = assessmentStore.findByReview(review.id(), review.attemptNo());
        when(query.findAssessmentViews(review.id(), review.attemptNo())).thenReturn(stored.stream()
                .map(assessment -> new ReviewQueryService.AssessmentView(
                        assessment.roleType().name(), assessment.checkpointKey(), assessment.status().name(),
                        assessment.summary(), assessment.reasonSummary(),
                        assessment.evidenceIds().stream().map(EvidenceId::value).toList(),
                        assessment.createdAt().toString()))
                .toList());
        int unknown = (int) stored.stream().filter(value -> value.status() == AssessmentStatus.UNKNOWN).count();
        when(query.findAssessmentCoverage(review.id(), review.attemptNo()))
                .thenReturn(new ReviewQueryService.AssessmentCoverageView(
                        stored.size(), stored.size(), stored.size() - unknown, 0, 0, unknown, 0, List.of()));
        ReviewReportService reports = new ReviewReportService(
                new InMemoryReviewReportStore(),
                query,
                new InMemoryHumanReviewItemStore(),
                decisionStore,
                registry,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-11T03:29:00Z"), ZoneOffset.UTC),
                conflicts);
        return reports.generate(review);
    }

    private Review initialReview() {
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(reviewStateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(reviewStateMachine, ReviewStage.PLANNING);
        review.transitionTo(reviewStateMachine, ReviewStage.INITIAL_REVIEW);
        for (RoleType roleType : List.of(RoleType.PRODUCT, RoleType.PROJECT, RoleType.FRONTEND, RoleType.BACKEND)) {
            review.activateRole(new RoleActivation(roleType, roleType.name().toLowerCase() + "-reviewer", false));
        }
        return review;
    }

    private void submitAllRequired(
            Review review,
            java.util.function.Function<RequiredSlot, AssessmentStatus> statusSelector) {
        List<RoleType> completedRoles = new ArrayList<>();
        for (RolePack rolePack : rolePacks.all()) {
            if (!rolePack.roleType().isCore()) {
                continue;
            }
            for (RolePack.Checkpoint checkpoint : rolePack.checklist()) {
                if (!checkpoint.required() || !checkpoint.hasStableKey()) {
                    continue;
                }
                RequiredSlot slot = new RequiredSlot(rolePack.roleType(), checkpoint.checkpointKey());
                AssessmentStatus status = statusSelector.apply(slot);
                assessments.submit(review, new AssessmentService.AssessmentSubmission(
                        metadata(review, "assessment-" + slot.roleType() + "-" + slot.checkpointKey()),
                        slot.roleType(), slot.checkpointKey(), status,
                        status == AssessmentStatus.UNKNOWN ? "当前授权证据不足。" : "该检查点已确认无问题。",
                        status.requiresReasonSummary() ? "需人工补充授权证据。" : null,
                        List.of()));
            }
            completedRoles.add(rolePack.roleType());
        }
        for (RoleType roleType : completedRoles) {
            assertThat(assessments.isCoverageComplete(review.id(), review.attemptNo(), roleType)).isTrue();
            review.completeInitialReview(roleType);
        }
    }

    private void addConflictingFacts(
            Review review, String subjectKey, RoleType supportRole, RoleType opposeRole) {
        assessmentStore.saveBatch(review.id(), review.attemptNo(), List.of(
                assessment(review, supportRole, subjectKey, AssessmentStatus.CONFIRMED),
                assessment(review, opposeRole, subjectKey, AssessmentStatus.GAP)));
        debateStore.saveClaim(claim(review.id(), supportRole, subjectKey, ClaimPosition.SUPPORT));
        debateStore.saveClaim(claim(review.id(), opposeRole, subjectKey, ClaimPosition.OPPOSE));
    }

    private ReviewAssessment assessment(
            Review review, RoleType roleType, String checkpointKey, AssessmentStatus status) {
        return new ReviewAssessment(
                review.id(), review.attemptNo(), roleType, checkpointKey, status,
                status == AssessmentStatus.GAP ? "该主题存在缺口。" : "该主题已确认。",
                status.requiresReasonSummary() ? "同主题角色结论相互矛盾。" : null,
                List.of(), ReviewAssessment.idempotencyKeyFor(
                        review.id(), review.attemptNo(), roleType, checkpointKey), Instant.now());
    }

    private Claim claim(
            ReviewId reviewId, RoleType roleType, String subjectKey, ClaimPosition position) {
        return new Claim(
                new ClaimId(UUID.randomUUID()), reviewId, roleType, subjectKey, ClaimSeverity.P1, position,
                position == ClaimPosition.OPPOSE ? "当前方案存在可追踪缺口。" : "当前方案已满足检查点。",
                "结构化评审结论。", List.of());
    }

    private ReviewCommandMetadata metadata(Review review, String key) {
        return new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey(key));
    }

    private record RequiredSlot(RoleType roleType, String checkpointKey) {
    }
}
