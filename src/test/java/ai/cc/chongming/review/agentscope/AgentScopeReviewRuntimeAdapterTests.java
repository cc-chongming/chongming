package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.AgentEventAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeRoleRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeStartRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentScopeReviewRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewDirectorHarnessFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.infrastructure.agentscope.RoleSubagentFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that a review has one active director and cancellation releases the next-attempt lock.
 *
 * @author wangli
 */
class AgentScopeReviewRuntimeAdapterTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsSecondDirectorThenAllowsNewAttemptAfterCancellation() {
        ReviewRuntimeContext firstAttempt = context(1);
        AgentScopeReviewRuntimeAdapter adapter = adapter();
        AgentRuntimeStartRequest firstStart = startRequest(firstAttempt);

        adapter.start(firstStart).block();

        assertThatThrownBy(() -> adapter.start(firstStart).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active director runtime");

        adapter.cancel(firstAttempt.runtimeId()).block();
        assertThatThrownBy(() -> adapter.resume(firstAttempt.runtimeId()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled runtime cannot be resumed");

        ReviewRuntimeContext secondAttempt = new ReviewRuntimeContext(
                firstAttempt.reviewId(), 2, firstAttempt.userId(), "trace-002", IntakeCancellation.neverCancelled());
        adapter.start(startRequest(secondAttempt)).block();
    }

    @Test
    void registersRoleWhenAdapterReplacesOnlyTheCancellationSignal() {
        ReviewRuntimeContext context = context(1);
        AgentScopeReviewRuntimeAdapter adapter = adapter();
        adapter.start(startRequest(context)).block();

        adapter.registerRole(new AgentRuntimeRoleRequest(
                context.runtimeId(),
                context,
                RoleType.PRODUCT,
                context.roleLabel(RoleType.PRODUCT),
                context.roleSessionId(RoleType.PRODUCT))).block();
    }

    @Test
    void rejectsRoleRegistrationWithoutRuntimeContext() {
        ReviewRuntimeContext context = context(1);
        AgentScopeReviewRuntimeAdapter adapter = adapter();
        adapter.start(startRequest(context)).block();

        assertThatThrownBy(() -> adapter.registerRole(new AgentRuntimeRoleRequest(
                context.runtimeId(), null, RoleType.PRODUCT, context.roleLabel(RoleType.PRODUCT),
                context.roleSessionId(RoleType.PRODUCT))).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("role request must use the active runtime context");
    }

    private AgentScopeReviewRuntimeAdapter adapter() {
        ReviewProperties reviewProperties = new ReviewProperties(temporaryDirectory.toString(), 8, 2);
        ReviewWorkspaceLayout workspaceLayout = new ReviewWorkspaceLayout(
                reviewProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        AgentScopeProperties agentScopeProperties = new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString());
        ModelGateway gateway = (request, cancellation) -> Mono.just(new ModelGateway.ModelResponse(
                "response-001", "test-model", "Review plan prepared.", new ModelGateway.Usage(1, 1, 2),
                ModelGateway.FinishReason.STOP, Duration.ofMillis(5), 1, request.traceId()));
        @SuppressWarnings("unchecked")
        ObjectProvider<io.agentscope.harness.agent.DistributedStore> storeProvider = Mockito.mock(ObjectProvider.class);
        return new AgentScopeReviewRuntimeAdapter(
                new ReviewDirectorHarnessFactory(workspaceLayout, gateway, agentScopeProperties, storeProvider),
                new RoleSubagentFactory(
                        new ai.cc.chongming.review.domain.role.RolePackRegistry(
                                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()),
                        gateway,
                        agentScopeProperties,
                        workspaceLayout),
                new AgentEventAdapter());
    }

    private AgentRuntimeStartRequest startRequest(ReviewRuntimeContext context) {
        return new AgentRuntimeStartRequest(
                context.runtimeId(), context.userId(), context.directorSessionId(), "Create the total review plan.", context);
    }

    private ReviewRuntimeContext context(int attemptNo) {
        return new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), attemptNo, "user-001", "trace-001", IntakeCancellation.neverCancelled());
    }
}
