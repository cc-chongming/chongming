package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * [AIREVIEW-PLAN-020#3.2] Verifies that the observer records authorized AS2 tool calls without
 * replacing their execution implementation or changing the harness tool policy.
 *
 * @author wangli
 */
class ScoutToolTraceCollectorTests {

    @Test
    void capturesNativeReadToolInputOutputAndTerminalStateByToolCallId() {
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        ToolUseBlock toolUse = new ToolUseBlock("call-1", "grep_files", Map.of("pattern", "ReviewService"));

        collector.onActing(
                        null,
                        null,
                        new ActingInput(List.of(toolUse)),
                        ignored -> Flux.just(
                                new ToolResultTextDeltaEvent("reply-1", "call-1", "grep_files", "src/main/java/ReviewService.java"),
                                new ToolResultTextDeltaEvent("reply-1", "call-1", "grep_files", ":42"),
                                new ToolResultEndEvent("reply-1", "call-1", "grep_files", ToolResultState.SUCCESS)))
                .collectList()
                .block();

        ScoutToolTraceCollector.ToolTrace trace = collector.find("call-1").orElseThrow();

        assertThat(trace.toolName()).isEqualTo("grep_files");
        assertThat(trace.input()).containsEntry("pattern", "ReviewService");
        assertThat(trace.outputText()).isEqualTo("src/main/java/ReviewService.java:42");
        assertThat(trace.state()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(trace.elapsedMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void capturesAuthorizedDirectorAndFinalizerToolsWithoutChangingTheActingInput() {
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        ToolUseBlock planWrite = new ToolUseBlock("call-plan", "plan_write", Map.of("plan", "Review plan"));
        ToolUseBlock completeInitialReview = new ToolUseBlock(
                "call-finalize", "complete_initial_review", Map.of("summary", "Initial review completed"));
        ActingInput input = new ActingInput(List.of(planWrite, completeInitialReview));
        AtomicReference<ActingInput> forwardedInput = new AtomicReference<>();

        collector.onActing(
                        null,
                        null,
                        input,
                        forwarded -> {
                            forwardedInput.set(forwarded);
                            return Flux.just(
                                    new ToolResultEndEvent(
                                            "reply-1", "call-plan", "plan_write", ToolResultState.SUCCESS),
                                    new ToolResultEndEvent(
                                            "reply-1",
                                            "call-finalize",
                                            "complete_initial_review",
                                            ToolResultState.SUCCESS));
                        })
                .collectList()
                .block();

        assertThat(forwardedInput.get()).isSameAs(input);
        assertThat(collector.find("call-plan").orElseThrow())
                .extracting(ScoutToolTraceCollector.ToolTrace::toolName, ScoutToolTraceCollector.ToolTrace::state)
                .containsExactly("plan_write", ToolResultState.SUCCESS);
        assertThat(collector.find("call-finalize").orElseThrow())
                .extracting(ScoutToolTraceCollector.ToolTrace::toolName, ScoutToolTraceCollector.ToolTrace::state)
                .containsExactly("complete_initial_review", ToolResultState.SUCCESS);
    }
}
