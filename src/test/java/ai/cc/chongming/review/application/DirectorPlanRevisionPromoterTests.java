package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.config.ReviewOrchestrationProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.support.FakeAgentRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * [AIREVIEW-PLAN-036#闭环] Verifies that a Director-authored plans/PLAN.md document is promoted
 * into a bounded public plan revision exactly once per content change: revises the plan and emits
 * PLAN_REVISED, never re-promotes unchanged content, and never mistakes the server-written
 * initial plan (plan-v1.json) for a revision.
 *
 * @author wangli
 */
class DirectorPlanRevisionPromoterTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void promotesNewPlanDocumentAndEmitsPlanRevised() throws IOException {
        StartFixture fixture = startFixture(3);
        writePlanDocument(fixture.workspace(),
                "# 修订计划\n修订原因：初审反馈\n- 复核需求范围\n- 补充边界用例");

        Optional<ReviewOrchestrationService.PlanRevision> revision =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());

        assertThat(revision).isPresent();
        assertThat(revision.get().plan().planVersion()).isEqualTo(2);
        assertThat(revision.get().plan().publicTasks()).containsExactly("复核需求范围", "补充边界用例");
        assertThat(revision.get().plan().changeReason()).isEqualTo("初审反馈");
        assertThat(fixture.service().plans(fixture.context())).hasSize(2);
        assertThat(fixture.service().events(fixture.context()))
                .extracting(ReviewOrchestrationService.OrchestrationEvent::type)
                .contains(ReviewOrchestrationService.OrchestrationEventType.PLAN_REVISED);
        assertThat(fixture.promoter().lastPromotedVersion(fixture.context().runtimeId())).hasValue(2);
    }

    @Test
    void doesNotPromoteServerInitialPlanWithoutAPlanDocument() {
        StartFixture fixture = startFixture(3);

        Optional<ReviewOrchestrationService.PlanRevision> revision =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());

        assertThat(revision).isEmpty();
        assertThat(fixture.service().plans(fixture.context())).hasSize(1);
        assertThat(fixture.service().plans(fixture.context()).getFirst().planVersion()).isEqualTo(1);
        assertThat(fixture.service().events(fixture.context()))
                .extracting(ReviewOrchestrationService.OrchestrationEvent::type)
                .doesNotContain(ReviewOrchestrationService.OrchestrationEventType.PLAN_REVISED);
        assertThat(fixture.promoter().lastPromotedVersion(fixture.context().runtimeId())).isEmpty();
    }

    @Test
    void doesNotPromoteUnchangedPlanDocumentTwice() throws IOException {
        StartFixture fixture = startFixture(3);
        String content = "# 修订计划\n修订原因：第二轮调整\n- 复核需求范围";
        writePlanDocument(fixture.workspace(), content);

        Optional<ReviewOrchestrationService.PlanRevision> first =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());
        Optional<ReviewOrchestrationService.PlanRevision> second =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());

        assertThat(first).isPresent();
        assertThat(first.get().plan().planVersion()).isEqualTo(2);
        assertThat(second).isEmpty();
        assertThat(fixture.service().plans(fixture.context())).hasSize(2);
        assertThat(revisionCount(fixture)).isEqualTo(1);
        assertThat(fixture.promoter().lastPromotedVersion(fixture.context().runtimeId())).hasValue(2);
    }

    @Test
    void promotesEachContentChangeExactlyOnceAsNextVersion() throws IOException {
        StartFixture fixture = startFixture(3);
        writePlanDocument(fixture.workspace(), "# 修订计划\n修订原因：第一轮\n- 任务一");
        Optional<ReviewOrchestrationService.PlanRevision> first =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());

        writePlanDocument(fixture.workspace(), "# 修订计划\n修订原因：第二轮\n- 任务一\n- 任务二");
        Optional<ReviewOrchestrationService.PlanRevision> second =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());
        Optional<ReviewOrchestrationService.PlanRevision> duplicateScan =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());

        assertThat(first).isPresent();
        assertThat(first.get().plan().planVersion()).isEqualTo(2);
        assertThat(second).isPresent();
        assertThat(second.get().plan().planVersion()).isEqualTo(3);
        assertThat(second.get().plan().publicTasks()).containsExactly("任务一", "任务二");
        assertThat(duplicateScan).isEmpty();
        assertThat(fixture.service().plans(fixture.context()))
                .extracting(plan -> plan.planVersion())
                .containsExactly(1, 2, 3);
        assertThat(revisionCount(fixture)).isEqualTo(2);
    }

    @Test
    void ignoresPlanDocumentWithoutATaskList() throws IOException {
        StartFixture fixture = startFixture(3);
        writePlanDocument(fixture.workspace(), "# 修订计划\n修订原因：说明性草稿\n这里只有叙述文字，没有任务清单。");

        Optional<ReviewOrchestrationService.PlanRevision> revision =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());

        assertThat(revision).isEmpty();
        assertThat(fixture.service().plans(fixture.context())).hasSize(1);
        assertThat(revisionCount(fixture)).isZero();
    }

    @Test
    void refusesPromotionWhenRevisionBoundIsReached() throws IOException {
        StartFixture fixture = startFixture(2);
        writePlanDocument(fixture.workspace(), "# 修订计划\n修订原因：第一次\n- 任务一");
        Optional<ReviewOrchestrationService.PlanRevision> allowed =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());

        writePlanDocument(fixture.workspace(), "# 修订计划\n修订原因：边界外\n- 任务二");
        Optional<ReviewOrchestrationService.PlanRevision> refused =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());
        Optional<ReviewOrchestrationService.PlanRevision> retried =
                fixture.promoter().promoteIfChanged(fixture.context(), fixture.workspace());

        assertThat(allowed).isPresent();
        assertThat(allowed.get().plan().planVersion()).isEqualTo(2);
        assertThat(refused).isEmpty();
        // The unchanged over-bound document is marked consumed so it is not retried each wake.
        assertThat(retried).isEmpty();
        assertThat(fixture.service().plans(fixture.context())).hasSize(2);
        assertThat(revisionCount(fixture)).isEqualTo(1);
    }

    private long revisionCount(StartFixture fixture) {
        return fixture.service().events(fixture.context()).stream()
                .filter(event -> event.type() == ReviewOrchestrationService.OrchestrationEventType.PLAN_REVISED)
                .count();
    }

    private StartFixture startFixture(int maxPlanRevisions) {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        ReviewOrchestrationService service = buildService(
                new FakeAgentRuntimeAdapter(), stateMachine, new ReviewOrchestrationProperties(maxPlanRevisions, 4));
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                new ReviewProperties(temporaryDirectory.toString(), 8, 2), new ObjectMapper());
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                review.id(), review.attemptNo(), "user-001", "trace-001", IntakeCancellation.neverCancelled());
        service.start(new ReviewOrchestrationService.StartRequest(
                review, context, List.of("Run mandatory role review"), "Initial total plan", "Begin review")).block();
        return new StartFixture(service, context, workspaceLayout.open(context),
                new DirectorPlanRevisionPromoter(service));
    }

    private ReviewOrchestrationService buildService(
            AgentRuntimeAdapter adapter, ReviewStateMachine stateMachine, ReviewOrchestrationProperties properties) {
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(reviewProperties, new ObjectMapper());
        RoleActivationService activationService = new RoleActivationService(
                new ReviewProtocolGuard(), new RolePackRegistry(new PathMatchingResourcePatternResolver()), reviewProperties);
        return new ReviewOrchestrationService(adapter, workspaceLayout, activationService, stateMachine, properties);
    }

    private void writePlanDocument(ReviewWorkspaceLayout.ReviewWorkspace workspace, String content)
            throws IOException {
        Files.writeString(workspace.plans().resolve("PLAN.md"), content, StandardCharsets.UTF_8);
    }

    private record StartFixture(
            ReviewOrchestrationService service,
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            DirectorPlanRevisionPromoter promoter) {
    }
}
