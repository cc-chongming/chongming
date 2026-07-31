package ai.cc.chongming.review.infrastructure.model;

import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException.Code;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * OpenAI chat-completions adapter usable by OpenAI-compatible and DashScope-compatible endpoints.
 *
 * @author wangli
 */
@Component
public class OpenAiCompatibleModelClient implements ModelProviderClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleModelClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public OpenAiCompatibleModelClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), objectMapper);
    }

    OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public ProviderResponse invoke(ProviderRequest request) {
        try {
            String payload = serialize(request);
            if (request.logConversation()) {
                LOGGER.info("MODEL_CONVERSATION_REQUEST traceId={} profile={} model={} endpoint={}\n{}",
                        request.request().traceId(), request.profile().profileId(), request.profile().modelName(), endpoint(request.baseUrl()), payload);
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint(request.baseUrl()))
                    .timeout(request.profile().timeout())
                    .header("Authorization", "Bearer " + request.apiKey())
                    .header("Content-Type", "application/json")
                    .header("X-Request-Id", request.request().traceId())
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (request.logConversation()) {
                LOGGER.info("MODEL_CONVERSATION_RESPONSE traceId={} profile={} status={}\n{}",
                        request.request().traceId(), request.profile().profileId(), response.statusCode(), response.body());
            }
            return parseResponse(response);
        } catch (HttpTimeoutException exception) {
            throw new ModelGatewayException(Code.MODEL_CALL_TIMEOUT, "Model call timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(Code.MODEL_CANCELLED, "Model call was interrupted", exception);
        } catch (IOException exception) {
            throw new ModelGatewayException(Code.MODEL_NETWORK_ERROR, "Model provider is unavailable", exception);
        }
    }

    private URI endpoint(URI baseUrl) {
        String value = baseUrl.toString().replaceAll("/+$", "");
        return URI.create(value + "/chat/completions");
    }

    private String serialize(ProviderRequest request) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", request.profile().modelName());
            payload.put("temperature", request.profile().temperature());
            payload.put("max_tokens", request.profile().maxTokens());
            payload.put("stream", false);
            ArrayNode messages = payload.putArray("messages");
            addMessage(messages, "system", request.request().systemInstruction());
            addMessage(messages, "user", request.request().publicContext());
            if (!request.request().tools().isEmpty()) {
                ArrayNode tools = payload.putArray("tools");
                for (ModelGateway.ToolDefinition tool : request.request().tools()) {
                    ObjectNode item = tools.addObject();
                    item.put("type", "function");
                    ObjectNode function = item.putObject("function");
                    function.put("name", tool.name());
                    function.put("description", tool.description());
                    function.set("parameters", objectMapper.valueToTree(tool.parameters()));
                    if (tool.strict() != null) function.put("strict", tool.strict());
                }
            }
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model request cannot be serialized", exception);
        }
    }

    private void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode message = messages.addObject();
        message.put("role", role);
        message.put("content", content);
    }

    private ProviderResponse parseResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 429) {
            throw new ModelGatewayException(Code.MODEL_RATE_LIMITED, "Model provider rate limit reached");
        }
        if (status >= 400 && status < 500) {
            throw new ModelGatewayException(
                    Code.MODEL_REQUEST_REJECTED,
                    "Model provider rejected the request with HTTP " + status);
        }
        if (status < 200 || status >= 300) {
            throw new ModelGatewayException(
                    Code.MODEL_PROVIDER_ERROR,
                    "Model provider returned HTTP " + status);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText("");
            java.util.List<ModelGateway.ToolCall> toolCalls = parseToolCalls(choice.path("message").path("tool_calls"));
            if (content.isBlank() && toolCalls.isEmpty()) {
                throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model response has no public text");
            }
            JsonNode usage = root.path("usage");
            return new ProviderResponse(
                    text(root, "id", "provider-response"),
                    content,
                    new ModelGateway.Usage(
                            usage.path("prompt_tokens").asLong(0),
                            usage.path("completion_tokens").asLong(0),
                            usage.path("total_tokens").asLong(0)),
                    finishReason(choice.path("finish_reason").asText()),
                    toolCalls);
        } catch (JsonProcessingException exception) {
            throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model response is not valid JSON", exception);
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText();
        return value.isBlank() ? fallback : value;
    }

    private java.util.List<ModelGateway.ToolCall> parseToolCalls(JsonNode calls) throws JsonProcessingException {
        if (!calls.isArray()) return java.util.List.of();
        java.util.List<ModelGateway.ToolCall> result = new java.util.ArrayList<>();
        Set<String> callIds = new HashSet<>();
        for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            String id = call.path("id").asText();
            String name = function.path("name").asText();
            if (id.isBlank() || name.isBlank() || !callIds.add(id)) {
                throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID,
                        "Model response contains an invalid tool call identity");
            }
            JsonNode input = objectMapper.readTree(function.path("arguments").asText());
            if (!input.isObject()) {
                throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID,
                        "Model tool call arguments must be a JSON object");
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> parameters = objectMapper.convertValue(input, java.util.Map.class);
            result.add(new ModelGateway.ToolCall(id, name, parameters));
        }
        return java.util.List.copyOf(result);
    }

    private ModelGateway.FinishReason finishReason(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "stop" -> ModelGateway.FinishReason.STOP;
            case "length" -> ModelGateway.FinishReason.LENGTH;
            case "tool_calls", "function_call" -> ModelGateway.FinishReason.TOOL_CALL;
            case "content_filter" -> ModelGateway.FinishReason.CONTENT_FILTER;
            default -> ModelGateway.FinishReason.UNKNOWN;
        };
    }
}
