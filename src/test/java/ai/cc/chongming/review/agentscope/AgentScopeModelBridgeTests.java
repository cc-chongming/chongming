package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.AgentScopeModelBridge;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the credential-safe model boundary used by AgentScope harnesses.
 *
 * @author wangli
 */
class AgentScopeModelBridgeTests {

    @Test
    void forwardsOnlyRolePermittedToolsToGateway() {
        AtomicReference<ModelGateway.ModelRequest> captured = new AtomicReference<>();
        ModelGateway gateway = (request, cancellation) -> {
            captured.set(request);
            return Mono.just(response());
        };
        AgentScopeModelBridge bridge = new AgentScopeModelBridge(
                gateway, context(), RoleType.PRODUCT, "role-reviewer", "Review public requirements", Set.of("searchText"));

        bridge.stream(
                List.of(new UserMessage("Assess the requirement")),
                List.of(ToolSchema.builder().name("searchText").description("Search").parameters(Map.of()).build()),
                null).blockFirst();

        assertThat(captured.get().roleType()).isEqualTo(RoleType.PRODUCT);
        assertThat(captured.get().allowedTools()).containsExactly("searchText");
        assertThat(captured.get().publicContext()).contains("Assess the requirement");
    }

    @Test
    void rejectsToolSchemaOutsideRolePackAllowlistBeforeCallingGateway() {
        ModelGateway gateway = (request, cancellation) -> Mono.error(new AssertionError("gateway must not be called"));
        AgentScopeModelBridge bridge = new AgentScopeModelBridge(
                gateway, context(), RoleType.PRODUCT, "role-reviewer", "Review public requirements", Set.of("searchText"));

        assertThatThrownBy(() -> bridge.stream(
                List.of(new UserMessage("Assess")),
                List.of(ToolSchema.builder().name("shell").description("Forbidden").parameters(Map.of()).build()),
                null).blockFirst())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AgentScope attempted to expose a non-permitted tool");
    }

    private ModelGateway.ModelResponse response() {
        return new ModelGateway.ModelResponse(
                "response-001", "model-001", "public result", new ModelGateway.Usage(1, 2, 3),
                ModelGateway.FinishReason.STOP, Duration.ofMillis(20), 1, "trace-001");
    }

    private ReviewRuntimeContext context() {
        return new ReviewRuntimeContext(new ReviewId(UUID.randomUUID()), 1, "user-001", "trace-001", IntakeCancellation.neverCancelled());
    }
}