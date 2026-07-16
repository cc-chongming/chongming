package ai.cc.chongming.review.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.domain.gateway.ModelGateway;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests OpenAI-compatible request and response normalization using a local JDK HTTP server.
 *
 * @author wangli
 */
class OpenAiCompatibleModelClientTests {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"chat-1","choices":[{"message":{"content":"{\\"tasks\\":[]}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
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
                        new RetryPolicy(0, Duration.ZERO)),
                new ModelGateway.ModelRequest(
                        new ReviewId(UUID.randomUUID()),
                        RoleType.BACKEND,
                        "role-reviewer",
                        "backend-v1",
                        "Return JSON only.",
                        "Public context only.",
                        Set.of("searchText"),
                        "trace-test")));

        assertThat(authorization).hasValue("Bearer safe-test-key");
        assertThat(requestBody.get()).contains("\"model\":\"test-model\"").contains("Public context only.");
        assertThat(response.publicText()).isEqualTo("{\"tasks\":[]}");
        assertThat(response.usage()).isEqualTo(new ModelGateway.Usage(3, 5, 8));
        assertThat(response.finishReason()).isEqualTo(ModelGateway.FinishReason.STOP);
    }
}
