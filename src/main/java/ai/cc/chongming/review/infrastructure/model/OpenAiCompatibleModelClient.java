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
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI chat-completions adapter usable by OpenAI-compatible and DashScope-compatible endpoints.
 * <p>
 * [AIREVIEW-PLAN-023#8]
 *
 * @author zyj
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
            String payload = serialize(request, false);
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

    private String serialize(ProviderRequest request, boolean stream) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", request.profile().modelName());
            payload.put("temperature", request.profile().temperature());
            payload.put("max_tokens", request.profile().maxTokens());
            payload.put("stream", stream);
            if (stream) {
                payload.putObject("stream_options").put("include_usage", true);
            }
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
        validateStatus(response.statusCode());
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String content = message.path("content").asText("");
            java.util.List<ModelGateway.ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
            if (content.isBlank() && toolCalls.isEmpty()) {
                throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model response has no public text");
            }
            JsonNode usage = root.path("usage");
            return new ProviderResponse(
                    text(root, "id", "provider-response"),
                    content,
                    "",
                    usage(usage),
                    finishReason(choice.path("finish_reason").asText()),
                    toolCalls);
        } catch (JsonProcessingException exception) {
            throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model response is not valid JSON", exception);
        }
    }

    private void validateStatus(int status) {
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
    }

    private static ModelGateway.Usage usage(JsonNode usage) {
        return new ModelGateway.Usage(
                usage.path("prompt_tokens").asLong(0),
                usage.path("completion_tokens").asLong(0),
                usage.path("total_tokens").asLong(0));
    }

    @Override
    public Flux<ProviderStreamChunk> stream(ProviderRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return Flux.defer(() -> streamResponse(request))
                .timeout(request.profile().timeout())
                .onErrorMap(TimeoutException.class,
                        timeout -> new ModelGatewayException(Code.MODEL_CALL_TIMEOUT, "Model stream timed out", timeout))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ProviderStreamChunk> streamResponse(ProviderRequest request) {
        HttpResponse<Stream<String>> response = sendStreamingRequest(request);
        try {
            validateStatus(response.statusCode());
        } catch (RuntimeException failure) {
            response.body().close();
            throw failure;
        }
        SseAccumulator accumulator = new SseAccumulator(request.request().traceId());
        return Flux.using(
                        response::body,
                        lines -> Flux.fromStream(lines)
                                .concatMap(line -> Flux.fromIterable(accumulator.accept(line)))
                                .concatWith(Flux.defer(accumulator::verifyCompleted)),
                        Stream::close)
                .onErrorMap(this::normalizeStreamFailure);
    }

    private HttpResponse<Stream<String>> sendStreamingRequest(ProviderRequest request) {
        try {
            String payload = serialize(request, true);
            if (request.logConversation()) {
                LOGGER.info(
                        "MODEL_CONVERSATION_STREAM_REQUEST traceId={} profile={} model={} endpoint={}\n{}",
                        request.request().traceId(),
                        request.profile().profileId(),
                        request.profile().modelName(),
                        endpoint(request.baseUrl()),
                        payload);
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint(request.baseUrl()))
                    .timeout(request.profile().timeout())
                    .header("Authorization", "Bearer " + request.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Request-Id", request.request().traceId())
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException exception) {
            throw new ModelGatewayException(Code.MODEL_CALL_TIMEOUT, "Model call timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(Code.MODEL_CANCELLED, "Model call was interrupted", exception);
        } catch (IOException exception) {
            throw new ModelGatewayException(Code.MODEL_NETWORK_ERROR, "Model provider is unavailable", exception);
        }
    }

    private Throwable normalizeStreamFailure(Throwable failure) {
        if (failure instanceof ModelGatewayException) {
            return failure;
        }
        if (failure instanceof UncheckedIOException unchecked) {
            return new ModelGatewayException(
                    Code.MODEL_NETWORK_ERROR, "Model provider stream was interrupted", unchecked.getCause());
        }
        return failure;
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

    private final class SseAccumulator {

        private final String fallbackResponseId;
        private final Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        private String responseId;
        private ModelGateway.Usage usage = new ModelGateway.Usage(0, 0, 0);
        private ModelGateway.FinishReason finishReason = ModelGateway.FinishReason.UNKNOWN;
        private boolean done;
        private boolean hasPublicText;
        private boolean hasReasoningOnly;
        private final List<String> rawSample = new java.util.ArrayList<>();

        private SseAccumulator(String traceId) {
            fallbackResponseId = traceId + ":provider-response";
            responseId = fallbackResponseId;
        }

        private List<ProviderStreamChunk> accept(String line) {
            if (line == null || line.isBlank() || line.startsWith(":")) {
                return List.of();
            }
            if (!line.startsWith("data:")) {
                return List.of();
            }
            String data = line.substring("data:".length()).stripLeading();
            if (rawSample.size() < 6) {
                rawSample.add(data.length() > 160 ? data.substring(0, 160) + "..." : data);
            }
            if ("[DONE]".equals(data)) {
                if (done) {
                    return List.of();
                }
                done = true;
                List<ModelGateway.ToolCall> completedTools = completedToolCalls();
                if (!hasPublicText && completedTools.isEmpty()) {
                    // Keep a bounded sample of the raw SSE lines so the next empty stream can be
                    // diagnosed from the log alone (proxy error JSON vs literally empty body).
                    LOGGER.warn("MODEL_STREAM_EMPTY_SAMPLE traceId={} sample={}", fallbackResponseId, rawSample);
                    // Distinguish a reasoning-only stream (provider thinking mode consumed the
                    // whole output) from a truly empty one so operators can diagnose provider
                    // side changes from the log alone.
                    if (hasReasoningOnly) {
                        throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID,
                                "Model stream contained only hidden reasoning content and no public text");
                    }
                    throw new ModelGatewayException(
                            Code.MODEL_RESPONSE_INVALID, "Model stream has no public text or tool call");
                }
                return List.of(new ProviderStreamChunk(
                        responseId, "", usage, finishReason, completedTools, true));
            }
            if (done) {
                throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model stream contains data after DONE");
            }
            return parseData(data);
        }

        private List<ProviderStreamChunk> parseData(String data) {
            try {
                JsonNode root = objectMapper.readTree(data);
                updateResponseId(root.path("id").asText(""));
                if (!root.path("usage").isMissingNode() && !root.path("usage").isNull()) {
                    usage = usage(root.path("usage"));
                }
                JsonNode choice = root.path("choices").path(0);
                if (!choice.isMissingNode()) {
                    String finish = choice.path("finish_reason").asText("");
                    if (!finish.isBlank()) {
                        finishReason = finishReason(finish);
                    }
                    JsonNode delta = choice.path("delta");
                    accumulateToolDeltas(delta.path("tool_calls"));
                    if (!delta.path("reasoning_content").asText("").isEmpty()) {
                        hasReasoningOnly = true;
                    }
                    String publicDelta = delta.path("content").asText("");
                    if (!publicDelta.isEmpty()) {
                        hasPublicText = true;
                        return List.of(new ProviderStreamChunk(
                                responseId,
                                publicDelta,
                                new ModelGateway.Usage(0, 0, 0),
                                ModelGateway.FinishReason.UNKNOWN,
                                List.of(),
                                false));
                    }
                }
                return List.of();
            } catch (JsonProcessingException exception) {
                throw new ModelGatewayException(
                        Code.MODEL_RESPONSE_INVALID, "Model stream contains malformed JSON", exception);
            }
        }

        private void updateResponseId(String candidate) {
            if (candidate == null || candidate.isBlank()) {
                return;
            }
            if (!fallbackResponseId.equals(responseId) && !responseId.equals(candidate)) {
                throw new ModelGatewayException(Code.MODEL_RESPONSE_INVALID, "Model stream response id changed");
            }
            responseId = candidate;
        }

        private void accumulateToolDeltas(JsonNode calls) {
            if (!calls.isArray()) {
                return;
            }
            int position = 0;
            for (JsonNode call : calls) {
                int index = call.path("index").asInt(position++);
                ToolCallAccumulator accumulator =
                        toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
                accumulator.append(
                        call.path("id").asText(""),
                        call.path("function").path("name").asText(""),
                        call.path("function").path("arguments").asText(""));
            }
        }

        private List<ModelGateway.ToolCall> completedToolCalls() {
            List<ModelGateway.ToolCall> completed = new ArrayList<>();
            Set<String> callIds = new HashSet<>();
            for (ToolCallAccumulator accumulator : toolCalls.values()) {
                String id = accumulator.id.toString();
                String name = accumulator.name.toString();
                if (id.isBlank() || name.isBlank() || !callIds.add(id)) {
                    throw new ModelGatewayException(
                            Code.MODEL_RESPONSE_INVALID, "Model stream contains an invalid tool call identity");
                }
                try {
                    JsonNode input = objectMapper.readTree(accumulator.arguments.toString());
                    if (input == null || !input.isObject()) {
                        throw new ModelGatewayException(
                                Code.MODEL_RESPONSE_INVALID, "Model tool call arguments must be a JSON object");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parameters = objectMapper.convertValue(input, Map.class);
                    completed.add(new ModelGateway.ToolCall(id, name, parameters));
                } catch (JsonProcessingException exception) {
                    throw new ModelGatewayException(
                            Code.MODEL_RESPONSE_INVALID, "Model stream tool arguments are incomplete", exception);
                }
            }
            return List.copyOf(completed);
        }

        private Flux<ProviderStreamChunk> verifyCompleted() {
            if (done) {
                return Flux.empty();
            }
            return Flux.error(new ModelGatewayException(
                    Code.MODEL_RESPONSE_INVALID, "Model stream ended before DONE"));
        }
    }

    private static final class ToolCallAccumulator {

        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        private void append(String idDelta, String nameDelta, String argumentsDelta) {
            id.append(idDelta == null ? "" : idDelta);
            name.append(nameDelta == null ? "" : nameDelta);
            arguments.append(argumentsDelta == null ? "" : argumentsDelta);
        }
    }
}
