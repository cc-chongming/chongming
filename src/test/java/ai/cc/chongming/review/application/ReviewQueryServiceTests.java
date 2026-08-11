package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;
import ai.cc.chongming.review.domain.repository.ReviewAssessmentStore;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewEventStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.repository.ReviewRepositories;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [AIREVIEW-PLAN-018#3.3][AIREVIEW-PLAN-023#5] Verifies persisted Context Scout conclusions and degradation.
 *
 * @author zyj
 */
class ReviewQueryServiceTests {

    @Test
    void prefersThePersistedCurrentAttemptScoutConclusion() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.PLANNING, 2, 7, List.of(), Map.of());
        ReviewEventStore eventStore = mock(ReviewEventStore.class);
        ReviewDebateStore debateStore = mock(ReviewDebateStore.class);
        HumanGateDecisionStore humanGateStore = mock(HumanGateDecisionStore.class);
        ContextScoutConclusionStore conclusionStore = mock(ContextScoutConclusionStore.class);
        ReviewRegistry reviewRegistry = mock(ReviewRegistry.class);
        when(eventStore.findLatest(reviewId)).thenReturn(Optional.of(event(
                reviewId, 12, 2, ReviewEventType.PLAN_CREATED, Map.of())));
        when(debateStore.findGateDraft(reviewId)).thenReturn(Optional.empty());
        when(humanGateStore.findLatest(reviewId)).thenReturn(Optional.empty());
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(review));
        when(conclusionStore.find(reviewId, 2)).thenReturn(Optional.of(new ContextScoutConclusion(
                reviewId,
                2,
                1,
                "上下文收集完成",
                List.of("src/main"),
                List.of("ReviewQueryService"),
                List.of("只公开授权信息"),
                List.of("历史数据需降级展示"),
                List.of("src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java"),
                Map.of("BACKEND", List.of("src/main/")),
                "{\"summary\":\"上下文收集完成\"}",
                Instant.parse("2026-08-10T08:00:00Z"))));
        ReviewQueryService service = new ReviewQueryService(
                eventStore,
                debateStore,
                mock(EvidenceLedgerService.class),
                humanGateStore,
                reviewRegistry,
                conclusionStore);

        ReviewQueryService.ContextScoutView view = service.findSummary(reviewId).orElseThrow().contextScout();

        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.publicSummary()).isEqualTo("上下文收集完成");
        assertThat(view.moduleRoots()).containsExactly("src/main");
        assertThat(view.entryPoints()).containsExactly("ReviewQueryService");
        assertThat(view.constraints()).containsExactly("只公开授权信息");
        assertThat(view.risks()).containsExactly("历史数据需降级展示");
        assertThat(view.evidencePaths()).hasSize(1);
        assertThat(view.roleScopes()).containsEntry("BACKEND", List.of("src/main/"));
        assertThat(view.rawPublicResult()).contains("上下文收集完成");
        assertThat(view.legacy()).isFalse();
    }

    @Test
    void exposesCurrentAttemptScoutDegradationFromItsDedicatedEvent() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.PLANNING, 2, 7, List.of(), Map.of());
        ReviewEventStore eventStore = mock(ReviewEventStore.class);
        ReviewDebateStore debateStore = mock(ReviewDebateStore.class);
        HumanGateDecisionStore humanGateStore = mock(HumanGateDecisionStore.class);
        ReviewRegistry reviewRegistry = mock(ReviewRegistry.class);
        ReviewEvent latest = event(reviewId, 12, 2, ReviewEventType.PLAN_CREATED, Map.of());
        ReviewEvent degraded = event(reviewId, 3, 2, ReviewEventType.CONTEXT_SCOUT_DEGRADED, Map.of(
                "status", "DEGRADED",
                "reasonCode", "MODEL_CALL_TIMEOUT",
                "publicSummary", "Context Scout 模型调用超时，已跳过项目上下文预处理，Director 将继续评审。"));
        when(eventStore.findLatest(reviewId)).thenReturn(Optional.of(latest));
        when(eventStore.findLatestByTypeAndAttempt(reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED, 2))
                .thenReturn(Optional.of(degraded));
        when(debateStore.findGateDraft(reviewId)).thenReturn(Optional.empty());
        when(humanGateStore.findLatest(reviewId)).thenReturn(Optional.empty());
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(review));
        ReviewQueryService service = new ReviewQueryService(
                eventStore,
                debateStore,
                mock(EvidenceLedgerService.class),
                humanGateStore,
                reviewRegistry);

        ReviewQueryService.ReviewSummary summary = service.findSummary(reviewId).orElseThrow();

        assertThat(summary.contextScout()).isNotNull();
        assertThat(summary.contextScout().status()).isEqualTo("DEGRADED");
        assertThat(summary.contextScout().reasonCode()).isEqualTo("MODEL_CALL_TIMEOUT");
        assertThat(summary.contextScout().publicSummary())
                .isEqualTo("Context Scout 模型调用超时，已跳过项目上下文预处理，Director 将继续评审。");
    }

    @Test
    void hidesScoutDegradationFromPreviousAttempt() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.PLANNING, 2, 7, List.of(), Map.of());
        ReviewEventStore eventStore = mock(ReviewEventStore.class);
        ReviewDebateStore debateStore = mock(ReviewDebateStore.class);
        HumanGateDecisionStore humanGateStore = mock(HumanGateDecisionStore.class);
        ReviewRegistry reviewRegistry = mock(ReviewRegistry.class);
        when(eventStore.findLatest(reviewId)).thenReturn(Optional.of(event(
                reviewId, 12, 2, ReviewEventType.PLAN_CREATED, Map.of())));
        when(eventStore.findLatestByTypeAndAttempt(reviewId, ReviewEventType.CONTEXT_SCOUT_DEGRADED, 2))
                .thenReturn(Optional.empty());
        when(debateStore.findGateDraft(reviewId)).thenReturn(Optional.empty());
        when(humanGateStore.findLatest(reviewId)).thenReturn(Optional.empty());
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(review));
        ReviewQueryService service = new ReviewQueryService(
                eventStore,
                debateStore,
                mock(EvidenceLedgerService.class),
                humanGateStore,
                reviewRegistry);

        assertThat(service.findSummary(reviewId).orElseThrow().contextScout()).isNull();
    }

    @Test
    void exposesAllPersistedClaimsEvenBeforeDebateTopicsExist() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewDebateStore debateStore = mock(ReviewDebateStore.class);
        Claim claim = new Claim(
                new ClaimId(UUID.randomUUID()),
                reviewId,
                RoleType.FRONTEND,
                "增量展示可行",
                ClaimSeverity.P2,
                ClaimPosition.SUPPORT,
                "前端已有 DiffViewer，增量数据展示无技术障碍。",
                "组件成熟。",
                List.of());
        when(debateStore.findClaims(reviewId)).thenReturn(List.of(claim));
        ReviewQueryService service = new ReviewQueryService(
                mock(ReviewEventStore.class),
                debateStore,
                mock(EvidenceLedgerService.class),
                mock(HumanGateDecisionStore.class),
                mock(ReviewRegistry.class));

        List<ReviewQueryService.ClaimView> views = service.findClaims(reviewId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).role()).isEqualTo("FRONTEND");
        assertThat(views.get(0).position()).isEqualTo("SUPPORT");
        assertThat(views.get(0).statement()).isEqualTo("前端已有 DiffViewer，增量数据展示无技术障碍。");
    }

    @Test
    void restoresActivatedRolesFromDurableProjectionWhenRegistryIsEmptyAfterRestart() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewEventStore eventStore = mock(ReviewEventStore.class);
        ReviewDebateStore debateStore = mock(ReviewDebateStore.class);
        HumanGateDecisionStore humanGateStore = mock(HumanGateDecisionStore.class);
        ContextScoutConclusionStore conclusionStore = mock(ContextScoutConclusionStore.class);
        ReviewRegistry reviewRegistry = mock(ReviewRegistry.class);
        ReviewRepositories reviewRepositories = mock(ReviewRepositories.class);
        Review restored = Review.restore(
                reviewId,
                ReviewStage.DEBATE_ROUND_1,
                1,
                4,
                List.of(new RoleActivation(RoleType.PRODUCT, "product-reviewer", true)),
                Map.of());
        when(eventStore.findLatest(reviewId)).thenReturn(Optional.of(event(
                reviewId, 12, 1, ReviewEventType.CHALLENGE_SUBMITTED, Map.of())));
        when(debateStore.findGateDraft(reviewId)).thenReturn(Optional.empty());
        when(humanGateStore.findLatest(reviewId)).thenReturn(Optional.empty());
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.empty());
        when(reviewRepositories.findReview(reviewId)).thenReturn(Optional.of(restored));
        when(conclusionStore.find(reviewId, 1)).thenReturn(Optional.of(new ContextScoutConclusion(
                reviewId,
                1,
                1,
                "重启后恢复的 Scout 结论",
                List.of("src/main"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                "{\"summary\":\"重启后恢复的 Scout 结论\"}",
                Instant.parse("2026-08-10T08:00:00Z"))));
        ObjectProvider<ReviewRepositories> reviewRepositoriesProvider = mock(ObjectProvider.class);
        when(reviewRepositoriesProvider.getIfAvailable()).thenReturn(reviewRepositories);
        ReviewQueryService service = new ReviewQueryService(
                eventStore,
                debateStore,
                mock(EvidenceLedgerService.class),
                humanGateStore,
                reviewRegistry,
                reviewRepositoriesProvider,
                conclusionStore);

        ReviewQueryService.ReviewSummary summary = service.findSummary(reviewId).orElseThrow();

        assertThat(summary.reviewVersion()).isEqualTo(4L);
        assertThat(summary.activatedRoles()).containsExactly(
                new ReviewQueryService.RoleActivationView("PRODUCT", "product-reviewer", true));
        assertThat(summary.contextScout().publicSummary()).isEqualTo("重启后恢复的 Scout 结论");
    }

    /**
     * [AIREVIEW-PLAN-024#方案5] The workbench projection exposes every persisted five-status
     * assessment sorted by role + checkpointKey together with the server-side coverage summary.
     */
    @Test
    void projectsFiveStatusAssessmentsWithServerSideCoverageSummary() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.INITIAL_REVIEW, 1, 3L, List.of(), Map.of());
        ReviewRegistry reviewRegistry = mock(ReviewRegistry.class);
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.of(review));
        RolePackRegistry rolePackRegistry = new RolePackRegistry(new PathMatchingResourcePatternResolver());
        InMemoryReviewAssessmentStore assessmentStore = new InMemoryReviewAssessmentStore();

        List<ReviewAssessment> batch = new ArrayList<>();
        int requiredCount = 0;
        List<Map.Entry<RoleType, RolePack.Checkpoint>> optionalSlots = new ArrayList<>();
        for (RolePack rolePack : rolePackRegistry.all()) {
            if (!rolePack.roleType().isCore()) {
                continue;
            }
            for (RolePack.Checkpoint checkpoint : rolePack.checklist()) {
                if (checkpoint.required() && checkpoint.hasStableKey()) {
                    batch.add(assessment(reviewId, rolePack.roleType(), checkpoint.checkpointKey(),
                            AssessmentStatus.CONFIRMED, null));
                    requiredCount++;
                } else if (checkpoint.hasStableKey()) {
                    optionalSlots.add(Map.entry(rolePack.roleType(), checkpoint));
                }
            }
        }
        assertThat(optionalSlots).hasSizeGreaterThanOrEqualTo(4);
        batch.add(assessment(reviewId, optionalSlots.get(0).getKey(),
                optionalSlots.get(0).getValue().checkpointKey(), AssessmentStatus.GAP, "缺口需要处置。"));
        batch.add(assessment(reviewId, optionalSlots.get(1).getKey(),
                optionalSlots.get(1).getValue().checkpointKey(), AssessmentStatus.UNKNOWN, "缺少授权证据。"));
        batch.add(assessment(reviewId, optionalSlots.get(2).getKey(),
                optionalSlots.get(2).getValue().checkpointKey(), AssessmentStatus.PARTIAL, "部分满足。"));
        batch.add(assessment(reviewId, optionalSlots.get(3).getKey(),
                optionalSlots.get(3).getValue().checkpointKey(), AssessmentStatus.NOT_APPLICABLE, null));
        assessmentStore.saveBatch(reviewId, 1, batch);

        ReviewQueryService service = queryServiceWithAssessments(reviewRegistry, assessmentStore, rolePackRegistry);

        ReviewQueryService.AssessmentsView view = service.findAssessments(reviewId);
        assertThat(view.attempt()).isEqualTo(1);
        ReviewQueryService.AssessmentCoverageView coverage = view.coverage();
        assertThat(coverage.required()).isEqualTo(requiredCount);
        assertThat(coverage.covered()).isEqualTo(requiredCount);
        assertThat(coverage.confirmed()).isEqualTo(requiredCount);
        assertThat(coverage.gap()).isEqualTo(1);
        assertThat(coverage.unknown()).isEqualTo(1);
        assertThat(coverage.partial()).isEqualTo(1);
        assertThat(coverage.notApplicable()).isEqualTo(1);
        assertThat(coverage.uncoveredCheckpoints()).isEmpty();

        List<String> expectedOrder = batch.stream()
                .map(value -> value.roleType().name() + ":" + value.checkpointKey())
                .sorted()
                .toList();
        assertThat(view.assessments())
                .extracting(value -> value.role() + ":" + value.checkpointKey())
                .containsExactlyElementsOf(expectedOrder);
        assertThat(view.assessments())
                .extracting(ReviewQueryService.AssessmentView::status)
                .contains(AssessmentStatus.CONFIRMED.name(), AssessmentStatus.PARTIAL.name(),
                        AssessmentStatus.GAP.name(), AssessmentStatus.UNKNOWN.name(),
                        AssessmentStatus.NOT_APPLICABLE.name());
        ReviewQueryService.AssessmentView gap = view.assessments().stream()
                .filter(value -> "GAP".equals(value.status())).findFirst().orElseThrow();
        assertThat(gap.summary()).isEqualTo("检查点结论摘要。");
        assertThat(gap.reasonSummary()).isEqualTo("缺口需要处置。");
    }

    @Test
    void returnsEmptyAssessmentProjectionBeforeAnyAttemptExists() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewRegistry reviewRegistry = mock(ReviewRegistry.class);
        when(reviewRegistry.find(reviewId)).thenReturn(Optional.empty());
        ReviewEventStore eventStore = mock(ReviewEventStore.class);
        when(eventStore.findLatest(reviewId)).thenReturn(Optional.empty());
        ReviewQueryService service = new ReviewQueryService(
                eventStore,
                mock(ReviewDebateStore.class),
                mock(EvidenceLedgerService.class),
                mock(HumanGateDecisionStore.class),
                reviewRegistry,
                mock(ObjectProvider.class),
                mock(ContextScoutConclusionStore.class),
                providerOf(new InMemoryReviewAssessmentStore()),
                providerOf(new RolePackRegistry(new PathMatchingResourcePatternResolver())));

        ReviewQueryService.AssessmentsView view = service.findAssessments(reviewId);

        assertThat(view.attempt()).isNull();
        assertThat(view.assessments()).isEmpty();
        assertThat(view.coverage().required()).isZero();
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ReviewQueryService queryServiceWithAssessments(
            ReviewRegistry reviewRegistry,
            ReviewAssessmentStore assessmentStore,
            RolePackRegistry rolePackRegistry) {
        ObjectProvider<ReviewRepositories> repositoriesProvider = mock(ObjectProvider.class);
        return new ReviewQueryService(
                mock(ReviewEventStore.class),
                mock(ReviewDebateStore.class),
                mock(EvidenceLedgerService.class),
                mock(HumanGateDecisionStore.class),
                reviewRegistry,
                repositoriesProvider,
                mock(ContextScoutConclusionStore.class),
                providerOf(assessmentStore),
                providerOf(rolePackRegistry));
    }

    private ReviewAssessment assessment(
            ReviewId reviewId,
            RoleType roleType,
            String checkpointKey,
            AssessmentStatus status,
            String reasonSummary) {
        return new ReviewAssessment(reviewId, 1, roleType, checkpointKey, status, "检查点结论摘要。",
                reasonSummary, List.of(),
                ReviewAssessment.idempotencyKeyFor(reviewId, 1, roleType, checkpointKey),
                Instant.parse("2026-08-10T09:00:00Z"));
    }

    private ReviewEvent event(
            ReviewId reviewId, long sequence, int attempt, ReviewEventType type, Map<String, String> payload) {
        return ReviewEvent.committed(sequence, new ReviewEventDraft(
                reviewId,
                attempt,
                type,
                ReviewStage.PLANNING,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-07-29T10:00:00Z"),
                1,
                payload));
    }
}
