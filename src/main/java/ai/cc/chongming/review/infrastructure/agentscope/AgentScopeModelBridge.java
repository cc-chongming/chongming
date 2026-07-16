package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * Adapts AgentScope model calls to the review application's credential-safe {@link ModelGateway}.
 *
 * @author wangli
 */
public final class AgentScopeModelBridge implements Model {

    private static final Set<String> INTERNAL_HARNESS_TOOLS = Set.of(
            "wait_async_results", "todo_write", "plan_enter", "plan_write", "plan_exit");

    private final ModelGateway modelGateway;
    private final ReviewRuntimeContext runtimeContext;
    private final RoleType roleType;
    private final String profileId;
    private final String systemInstruction;
    private final Set<String> permittedTools;

    public AgentScopeModelBridge(
            ModelGateway modelGateway,
            ReviewRuntimeContext runtimeContext,
            RoleType roleType,
            String profileId,
            String systemInstruction,
            Set<String> permittedTools) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        this.roleType = Objects.requireNonNull(roleType, "roleType must not be null");
        this.profileId = requireText(profileId, "profileId");
        this.systemInstruction = requireText(systemInstruction, "systemInstruction");
        Set<String> effectiveTools = new LinkedHashSet<>(INTERNAL_HARNESS_TOOLS);
        if (permittedTools != null) {
            effectiveTools.addAll(permittedTools);
        }
        this.permittedTools = Set.copyOf(effectiveTools);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        List<Msg> safeMessages = messages == null ? List.of() : List.copyOf(messages);
        Set<String> requestedTools = toolNames(tools);
        if (!permittedTools.containsAll(requestedTools)) {
            return Flux.error(new IllegalArgumentException("AgentScope attempted to expose a non-permitted tool"));
        }
        IntakeCancellation cancellation = runtimeContext.cancellation();
        ModelGateway.ModelRequest request = new ModelGateway.ModelRequest(
                runtimeContext.reviewId(),
                roleType,
                profileId,
                "agentscope-bridge-v1",
                systemInstruction,
                publicContext(safeMessages),
                requestedTools,
                runtimeContext.traceId());
        return modelGateway.generate(request, cancellation).map(this::toResponse).flux();
    }

    @Override
    public String getModelName() {
        return profileId;
    }

    private ChatResponse toResponse(ModelGateway.ModelResponse response) {
        ModelGateway.Usage usage = response.usage();
        return ChatResponse.builder()
                .id(response.responseId())
                .content(List.of(TextBlock.builder().text(response.publicText()).build()))
                .usage(new ChatUsage(
                        safeInt(usage.inputTokens()),
                        safeInt(usage.outputTokens()),
                        safeInt(usage.totalTokens()),
                        seconds(response.latency())))
                .metadata(Map.of("traceId", response.traceId(), "attempts", response.attempts()))
                .finishReason(response.finishReason().name().toLowerCase())
                .build();
    }

    private String publicContext(Collection<Msg> messages) {
        return messages.stream()
                .map(message -> message.getRole().name() + ": " + safeText(message))
                .collect(Collectors.joining("\n"));
    }

    private String safeText(Msg message) {
        String text = message.getTextContent();
        return text == null ? "" : text;
    }

    private Set<String> toolNames(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (ToolSchema tool : tools) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                throw new IllegalArgumentException("AgentScope tool schema must have a name");
            }
            names.add(tool.getName());
        }
        return Set.copyOf(names);
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(value);
    }

    private static double seconds(Duration latency) {
        return latency.toNanos() / 1_000_000_000.0d;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
