package ai.cc.chongming.review.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.gateway.ModelProfile;
import ai.cc.chongming.review.domain.gateway.ModelProfile.Provider;
import ai.cc.chongming.review.domain.gateway.ModelProfile.RetryPolicy;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests OpenAI-compatible request and response normalization using a local JDK HTTP server.
 * <p>
 * [AIREVIEW-PLAN-023#8][AIREVIEW-PLAN-024#6]
 *
 * @author zyj
 */
class OpenAiCompatibleModelClientTests {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("""
            {"id":"chat-1","choices":[{"message":{"content":"{\\"tasks\\":[]}","reasoning_content":"先判断评审范围。"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
            """);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/slow/chat/completions", exchange -> {
            try {
                // Outlasts the short profile timeout so the client classifies the wait.
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsCredentialAndNormalizesOpenAiChatCompletion() {
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());
        ModelProviderClient.ProviderResponse response = client.invoke(new ModelProviderClient.ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1"),
                "safe-test-key",
                new ModelProfile(
                        "role-reviewer",
                        Provider.OPENAI_COMPATIBLE,
                        "test-model",
                        0.2d,
                        Duration.ofSeconds(2),
                        128,
                        new RetryPolicy(0, Duration.ZERO),
                        null),
                new ModelGateway.ModelRequest(
                        new ReviewId(UUID.randomUUID()),
                        RoleType.BACKEND,
                        "role-reviewer",
                        "backend-v1",
                        "Return JSON only.",
                        "Public context only.",
                        Set.of("searchText"),
                        "trace-test"),
                false));

        assertThat(authorization).hasValue("Bearer safe-test-key");
        assertThat(requestBody.get()).contains("\"model\":\"test-model\"").contains("Public context only.");
        assertThat(response.publicText()).isEqualTo("{\"tasks\":[]}");
        assertThat(response.thinkingText()).isBlank();
        assertThat(response.usage()).isEqualTo(new ModelGateway.Usage(3, 5, 8));
        assertThat(response.finishReason()).isEqualTo(ModelGateway.FinishReason.STOP);
    }

    @Test
    void includesProviderStatusAndSafeErrorMessageForRejectedRequest() {
        responseStatus.set(400);
        responseBody.set("{\"error\":{\"message\":\"The model does not exist\"}}");
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());

        assertThatThrownBy(() -> client.invoke(request()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(exception -> assertThat(((ModelGatewayException) exception).code())
                        .isEqualTo(ModelGatewayException.Code.MODEL_REQUEST_REJECTED))
                .hasMessageContaining("HTTP 400")
                .hasMessageNotContaining("The model does not exist");
    }

    @Test
    void sendsToolSchemaAndAcceptsToolOnlyResponse() {
        responseBody.set("""
                {"id":"chat-tool-1","choices":[{"message":{"content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"submit_claim","arguments":"{\\"subjectKey\\":\\"api\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
                """);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());
        ModelGateway.ModelRequest modelRequest = new ModelGateway.ModelRequest(
                new ReviewId(UUID.randomUUID()), RoleType.BACKEND, "role-reviewer", "backend-v1",
                "Return tool calls.", "Public context.", Set.of("submit_claim"),
                List.of(new ModelGateway.ToolDefinition("submit_claim", "Submit a public claim",
                        Map.of("type", "object", "properties", Map.of("subjectKey", Map.of("type", "string"))), true)),
                "trace-tool");

        ModelProviderClient.ProviderResponse response = client.invoke(new ModelProviderClient.ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1"), "safe-test-key",
                profile(), modelRequest, false));

        assertThat(requestBody.get()).contains("\"tools\"").contains("\"submit_claim\"").contains("\"strict\":true");
        assertThat(response.publicText()).isBlank();
        assertThat(response.toolCalls()).containsExactly(new ModelGateway.ToolCall("call-1", "submit_claim", Map.of("subjectKey", "api")));
        assertThat(response.finishReason()).isEqualTo(ModelGateway.FinishReason.TOOL_CALL);
    }

    @Test
    void rejectsMalformedToolCallResponse() {
        responseBody.set("""
                {"id":"chat-tool-1","choices":[{"message":{"tool_calls":[{"id":"","function":{"name":"submit_claim","arguments":"[]"}}]},"finish_reason":"tool_calls"}],"usage":{}}
                """);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());

        assertThatThrownBy(() -> client.invoke(request()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(exception -> assertThat(((ModelGatewayException) exception).code())
                        .isEqualTo(ModelGatewayException.Code.MODEL_RESPONSE_INVALID));
    }

    private ModelProviderClient.ProviderRequest request() {
        return new ModelProviderClient.ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1"),
                "safe-test-key",
                profile(),
                new ModelGateway.ModelRequest(
                        new ReviewId(UUID.randomUUID()),
                        RoleType.BACKEND,
                        "role-reviewer",
                        "backend-v1",
                        "Return JSON only.",
                        "Public context only.",
                        Set.of("searchText"),
                        "trace-test"),
                false);
    }

    private ModelProfile profile() {
        return new ModelProfile("role-reviewer", Provider.OPENAI_COMPATIBLE, "test-model", 0.2d,
                Duration.ofSeconds(2), 128, new RetryPolicy(0, Duration.ZERO), null);
    }

    @Test
    void rejectsReasoningOnlyResponseInsteadOfTreatingHiddenReasoningAsPublicText() {
        responseBody.set("""
                {"id":"chat-reasoning-1","choices":[{"message":{"content":"","reasoning_content":"内部推理"},"finish_reason":"stop"}],"usage":{}}
                """);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());

        assertThatThrownBy(() -> client.invoke(request()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(exception -> assertThat(((ModelGatewayException) exception).code())
                        .isEqualTo(ModelGatewayException.Code.MODEL_RESPONSE_INVALID));
    }

    @Test
    void streamsPublicTextAndAggregatesToolArgumentsOnlyAtDone() {
        responseBody.set("""
                data: {"id":"chat-stream-1","choices":[{"delta":{"content":"公开"},"finish_reason":null}]}
                data: {"id":"chat-stream-1","choices":[{"delta":{"content":"结论"},"finish_reason":null}]}
                data: {"id":"chat-stream-1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"submit_claim","arguments":"{\\\"subject"}}]},"finish_reason":null}]}
                data: {"id":"chat-stream-1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"Key\\\":\\\"api\\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
                data: [DONE]
                """);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());

        List<ModelProviderClient.ProviderStreamChunk> chunks = client.stream(request()).collectList().block();

        assertThat(requestBody.get()).contains("\"stream\":true");
        assertThat(chunks).hasSize(3);
        assertThat(chunks.subList(0, 2)).extracting(ModelProviderClient.ProviderStreamChunk::publicTextDelta)
                .containsExactly("公开", "结论");
        ModelProviderClient.ProviderStreamChunk terminal = chunks.getLast();
        assertThat(terminal.terminal()).isTrue();
        assertThat(terminal.toolCalls()).containsExactly(
                new ModelGateway.ToolCall("call-1", "submit_claim", Map.of("subjectKey", "api")));
        assertThat(terminal.usage()).isEqualTo(new ModelGateway.Usage(3, 5, 8));
        assertThat(terminal.finishReason()).isEqualTo(ModelGateway.FinishReason.TOOL_CALL);
    }

    @Test
    void rejectsMalformedSseChunk() {
        responseBody.set("""
                data: {not-json}
                data: [DONE]
                """);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());

        assertThatThrownBy(() -> client.stream(request()).collectList().block())
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(exception -> assertThat(((ModelGatewayException) exception).code())
                        .isEqualTo(ModelGatewayException.Code.MODEL_RESPONSE_INVALID));
    }

    @Test
    void reportsReasoningOnlyStreamsDistinctlyFromEmptyStreams() {
        responseBody.set("""
                data: {"id":"chat-stream-r1","choices":[{"delta":{"reasoning_content":"内部推理"},"finish_reason":null}]}
                data: [DONE]
                """);
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());

        assertThatThrownBy(() -> client.stream(request()).collectList().block())
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(exception -> assertThat(exception.getMessage())
                        .contains("only hidden reasoning content"));
    }

    @Test
    void classifiesRequestTimeoutAsModelCallTimeoutSoBreakerAndMetricsCanCountIt() {
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), new ObjectMapper());
        ModelProviderClient.ProviderRequest slowRequest = new ModelProviderClient.ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/slow"),
                "safe-test-key",
                new ModelProfile("role-reviewer", Provider.OPENAI_COMPATIBLE, "test-model", 0.2d,
                        Duration.ofMillis(250), 128, new RetryPolicy(0, Duration.ZERO), null),
                new ModelGateway.ModelRequest(
                        new ReviewId(UUID.randomUUID()),
                        RoleType.BACKEND,
                        "role-reviewer",
                        "backend-v1",
                        "Return JSON only.",
                        "Public context only.",
                        Set.of("searchText"),
                        "trace-timeout"),
                false);

        assertThatThrownBy(() -> client.invoke(slowRequest))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(exception -> assertThat(((ModelGatewayException) exception).code())
                        .isEqualTo(ModelGatewayException.Code.MODEL_CALL_TIMEOUT));
    }

    @Test
    void providerInterfaceUsesExplicitNonStreamingFallback() {
        ModelProviderClient fallbackClient = ignored -> new ModelProviderClient.ProviderResponse(
                "fallback-1",
                "完整结论",
                new ModelGateway.Usage(1, 2, 3),
                ModelGateway.FinishReason.STOP);

        ModelProviderClient.ProviderStreamChunk chunk = fallbackClient.stream(request()).single().block();

        assertThat(chunk.publicTextDelta()).isEqualTo("完整结论");
        assertThat(chunk.terminal()).isTrue();
    }
}
