package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * [AIREVIEW-PLAN-019#3.1] Verifies that the Scout observes AS2 native read-tool calls without
 * replacing their execution implementation.
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
    void ignoresAnyToolOutsideTheScoutReadOnlyAllowlist() {
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        ToolUseBlock toolUse = new ToolUseBlock("call-unsafe", "write_file", Map.of("path", "README.md"));

        collector.onActing(
                        null,
                        null,
                        new ActingInput(List.of(toolUse)),
                        ignored -> Flux.just(new ToolResultEndEvent(
                                "reply-1", "call-unsafe", "write_file", ToolResultState.SUCCESS)))
                .collectList()
                .block();

        assertThat(collector.find("call-unsafe")).isEmpty();
    }

    @Test
    void ignoresRootListingBecauseTheInitManifestOwnsRepositoryReconnaissance() {
        ScoutToolTraceCollector collector = new ScoutToolTraceCollector();
        ToolUseBlock toolUse = new ToolUseBlock("call-list-root", "list_files", Map.of("path", "."));

        collector.captureModelToolUse(toolUse);

        assertThat(collector.find("call-list-root")).isEmpty();
    }
}
