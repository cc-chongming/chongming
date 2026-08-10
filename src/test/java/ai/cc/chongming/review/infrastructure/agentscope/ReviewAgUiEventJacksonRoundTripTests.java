package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-022#7.1] Verifies that the AG-UI events the runtime trace persists can be
 * restored through Jackson's {@code @JsonTypeInfo}/{@code @JsonSubTypes} contract (risk R1).
 *
 * @author wangli
 */
class ReviewAgUiEventJacksonRoundTripTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void restoresEveryMappedRuntimeEventTypeToAnEqualRecord() throws Exception {
        List<AguiEvent> events = List.of(
                new AguiEvent.RunStarted("review:11111111-1111-1111-1111-111111111111", "run-1"),
                new AguiEvent.ReasoningMessageStart("thread-1", "run-1", "m-1:thinking:0", "assistant"),
                new AguiEvent.ReasoningMessageContent("thread-1", "run-1", "m-1:thinking:0", "审查发现刷新令牌实现存在风险。"),
                new AguiEvent.ReasoningMessageEnd("thread-1", "run-1", "m-1:thinking:0"),
                new AguiEvent.TextMessageStart("thread-1", "run-1", "m-1", "assistant"),
                new AguiEvent.TextMessageContent("thread-1", "run-1", "m-1", "已提交 P1 反对意见"),
                new AguiEvent.TextMessageEnd("thread-1", "run-1", "m-1"),
                new AguiEvent.ToolCallStart("thread-1", "run-1", "call-1", "open_debate_topic"),
                new AguiEvent.ToolCallEnd("thread-1", "run-1", "call-1"),
                new AguiEvent.ToolCallResult("thread-1", "run-1", "call-1", "工具状态：SUCCESS", "tool", "reply-1"),
                toolObservation(),
                new AguiEvent.RunFinished("thread-1", "run-1"));

        for (AguiEvent event : events) {
            String json = objectMapper.writeValueAsString(event);
            AguiEvent restored = objectMapper.readValue(json, AguiEvent.class);
            assertThat(restored).as("round-trip of %s", event.getClass().getSimpleName()).isEqualTo(event);
        }
    }

    @Test
    void writesTheTypeDiscriminatorIntoThePersistedPayload() throws Exception {
        String json = objectMapper.writeValueAsString(
                new AguiEvent.TextMessageStart("thread-1", "run-1", "m-1", "assistant"));

        assertThat(json).contains("\"type\"");
        assertThat(objectMapper.readTree(json).path("type").asText()).isEqualTo("TEXT_MESSAGE_START");
    }

    private AguiEvent.Custom toolObservation() {
        return new AguiEvent.Custom(
                "thread-1",
                "run-1",
                ReviewAgUiEventMapper.SCOUT_TOOL_CALL_EVENT_NAME,
                Map.ofEntries(
                        Map.entry("schemaVersion", 1),
                        Map.entry("phase", "completed"),
                        Map.entry("reviewId", "11111111-1111-1111-1111-111111111111"),
                        Map.entry("attemptNo", 1),
                        Map.entry("runtimeId", "run-1"),
                        Map.entry("toolCallId", "call-1"),
                        Map.entry("toolName", "read_file"),
                        Map.entry("input", Map.of("path", "/repo/src/main/App.java")),
                        Map.entry("output", Map.of("text", "class App {}")),
                        Map.entry("status", "SUCCESS"),
                        Map.entry("elapsedMs", 42),
                        Map.entry("truncated", false)));
    }
}
