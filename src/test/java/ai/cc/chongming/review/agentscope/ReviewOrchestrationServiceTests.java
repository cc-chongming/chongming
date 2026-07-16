package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewOrchestrationService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.RoleActivationService;
import ai.cc.chongming.review.config.ReviewOrchestrationProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.role.RolePackRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.support.FakeAgentRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies director total planning, mandatory-role launch, bounded revisions and cancellation.
 *
 * @author wangli
 */
class ReviewOrchestrationServiceTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsCoreRolesRecordsPlanRevisionAndCancelsAttempt() {
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(reviewProperties, new ObjectMapper());
        RoleActivationService activationService = new RoleActivationService(
                new ReviewProtocolGuard(), new RolePackRegistry(new PathMatchingResourcePatternResolver()), reviewProperties);
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        ReviewOrchestrationService service = new ReviewOrchestrationService(
                new FakeAgentRuntimeAdapter(), workspaceLayout, activationService, stateMachine,
                new ReviewOrchestrationProperties(3));
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                review.id(), review.attemptNo(), "user-001", "trace-001", IntakeCancellation.neverCancelled());

        ReviewOrchestrationService.StartResult result = service.start(new ReviewOrchestrationService.StartRequest(
                review, context, List.of("Run mandatory role review"), "Initial total plan", "Begin review")).block();

        assertThat(result.coreActivations()).hasSize(4);
        assertThat(review.stage()).isEqualTo(ReviewStage.INITIAL_REVIEW);
        assertThat(review.roleActivations()).hasSize(4);
        assertThat(service.revisePlan(context, workspaceLayout.open(context), List.of("Resolve conflict"), "Conflict found")
                .plan().planVersion()).isEqualTo(2);

        service.cancel(review, context).block();
        assertThat(review.stage()).isEqualTo(ReviewStage.CANCELLED);
        assertThat(service.events(context)).extracting(ReviewOrchestrationService.OrchestrationEvent::type)
                .contains(ReviewOrchestrationService.OrchestrationEventType.PLAN_CREATED,
                        ReviewOrchestrationService.OrchestrationEventType.PLAN_REVISED,
                        ReviewOrchestrationService.OrchestrationEventType.CANCELLED);
    }
}