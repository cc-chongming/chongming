package ai.cc.chongming.review.infrastructure.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * [AIREVIEW-PLAN-019#3.1] Observes the AS2 native Scout read-tool lifecycle through the official
 * acting middleware. It is deliberately observational: it never replaces a native tool, changes
 * its parameters, or makes filesystem decisions.
 *
 * @author wangli
 */
public final class ScoutToolTraceCollector implements MiddlewareBase {

    private static final Set<String> ALLOWED_TOOL_NAMES =
            Set.of("glob_files", "grep_files", "read_file");

    private final ConcurrentMap<String, TraceState> traces = new ConcurrentHashMap<>();

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input != null && input.toolCalls() != null) {
            input.toolCalls().forEach(this::captureStart);
        }
        return next.apply(input).doOnNext(this::captureEvent);
    }

    /** Returns a point-in-time trace snapshot. Callers cannot mutate the collector state. */
    public Optional<ToolTrace> find(String toolCallId) {
        TraceState state = traces.get(toolCallId);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    /** Releases the attempt-local trace after a preview run has ended. */
    public void clear() {
        traces.clear();
    }

    /**
     * Receives the already-validated model tool call before AS2 emits {@code ToolCallStartEvent}.
     * Result collection remains on the official {@link MiddlewareBase#onActing} stream.
     */
    public void captureModelToolUse(ToolUseBlock toolUse) {
        captureStart(toolUse);
    }

    private void captureStart(ToolUseBlock toolUse) {
        if (toolUse == null || !isAllowed(toolUse.getName()) || blank(toolUse.getId())) {
            return;
        }
        traces.putIfAbsent(
                toolUse.getId(),
                new TraceState(toolUse.getName(), copyInput(toolUse.getInput()), System.nanoTime()));
    }

    private void captureEvent(AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent delta) {
            TraceState state = traces.get(delta.getToolCallId());
            if (state != null && state.toolName.equals(delta.getToolCallName())) {
                state.append(delta.getDelta());
            }
            return;
        }
        if (event instanceof ToolResultEndEvent end) {
            TraceState state = traces.get(end.getToolCallId());
            if (state != null && state.toolName.equals(end.getToolCallName())) {
                state.complete(end.getState());
            }
        }
    }

    private static Map<String, Object> copyInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static boolean isAllowed(String toolName) {
        return ALLOWED_TOOL_NAMES.contains(toolName);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Immutable browser-mapping input, preserving only the native read-tool observation. */
    public record ToolTrace(
            String toolName,
            Map<String, Object> input,
            String outputText,
            ToolResultState state,
            long elapsedMillis) {}

    private static final class TraceState {
        private final String toolName;
        private final Map<String, Object> input;
        private final long startedAtNanos;
        private final StringBuilder output = new StringBuilder();
        private ToolResultState state = ToolResultState.RUNNING;
        private long completedAtNanos;

        private TraceState(String toolName, Map<String, Object> input, long startedAtNanos) {
            this.toolName = toolName;
            this.input = input;
            this.startedAtNanos = startedAtNanos;
        }

        private synchronized void append(String delta) {
            if (delta != null) {
                output.append(delta);
            }
        }

        private synchronized void complete(ToolResultState terminalState) {
            state = terminalState == null ? ToolResultState.ERROR : terminalState;
            completedAtNanos = System.nanoTime();
        }

        private synchronized ToolTrace snapshot() {
            long finished = completedAtNanos == 0L ? System.nanoTime() : completedAtNanos;
            return new ToolTrace(
                    toolName,
                    input,
                    output.toString(),
                    state,
                    Math.max(0L, (finished - startedAtNanos) / 1_000_000L));
        }
    }
}
