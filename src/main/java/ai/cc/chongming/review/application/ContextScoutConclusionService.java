package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-023#5] Validates the Scout's public JSON and persists a readable fallback when parsing fails.
 *
 * @author zyj
 */
@Service
public class ContextScoutConclusionService {

    private static final int SCHEMA_VERSION = 1;
    private static final int FALLBACK_SUMMARY_LIMIT = 2_000;

    private final ContextScoutConclusionStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ContextScoutConclusionService(ContextScoutConclusionStore store, ObjectMapper objectMapper) {
        this(store, objectMapper, Clock.systemUTC());
    }

    ContextScoutConclusionService(ContextScoutConclusionStore store, ObjectMapper objectMapper, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public ContextScoutConclusion capture(ReviewId reviewId, int attemptNo, String rawPublicResult) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        if (rawPublicResult == null || rawPublicResult.isBlank()) {
            throw new IllegalArgumentException("rawPublicResult must not be blank");
        }
        ParsedConclusion parsed = parse(rawPublicResult);
        ContextScoutConclusion conclusion = new ContextScoutConclusion(
                reviewId,
                attemptNo,
                SCHEMA_VERSION,
                parsed.summary(),
                parsed.moduleRoots(),
                parsed.entryPoints(),
                parsed.constraints(),
                parsed.risks(),
                parsed.evidencePaths(),
                parsed.roleScopes(),
                rawPublicResult,
                Instant.now(clock));
        store.save(conclusion);
        return conclusion;
    }

    private ParsedConclusion parse(String rawPublicResult) {
        try {
            JsonNode root = objectMapper.readTree(jsonObject(rawPublicResult));
            if (root == null || !root.isObject()) {
                return fallback(rawPublicResult);
            }
            String summary = text(root.get("summary"));
            if (summary == null) {
                summary = fallbackSummary(rawPublicResult);
            }
            return new ParsedConclusion(
                    summary,
                    textList(root.get("moduleRoots")),
                    textList(root.get("entryPoints")),
                    textList(root.get("constraints")),
                    textList(root.get("risks")),
                    textList(root.get("evidencePaths")),
                    roleScopes(root.get("roleScopes")));
        } catch (Exception ignored) {
            return fallback(rawPublicResult);
        }
    }

    private ParsedConclusion fallback(String rawPublicResult) {
        return new ParsedConclusion(
                fallbackSummary(rawPublicResult), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private static String jsonObject(String rawPublicResult) {
        int start = rawPublicResult.indexOf('{');
        int end = rawPublicResult.lastIndexOf('}');
        return start >= 0 && end >= start ? rawPublicResult.substring(start, end + 1) : rawPublicResult;
    }

    private static String fallbackSummary(String rawPublicResult) {
        String normalized = rawPublicResult.strip();
        return normalized.length() <= FALLBACK_SUMMARY_LIMIT
                ? normalized
                : normalized.substring(0, FALLBACK_SUMMARY_LIMIT) + "…";
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().isBlank() ? node.textValue() : null;
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = textItem(item);
            if (value != null) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    /**
     * The Scout frequently emits structured items such as {@code {path, evidence, note}} or
     * {@code {risk, evidence, note}}; flattening their readable fields keeps the conclusion grid
     * populated instead of silently dropping every non-string element.
     */
    private static String textItem(JsonNode item) {
        if (item == null) {
            return null;
        }
        if (item.isTextual()) {
            return item.textValue().isBlank() ? null : item.textValue();
        }
        if (item.isObject()) {
            StringBuilder builder = new StringBuilder();
            appendFields(builder, item, "path", "risk", "constraint", "entryPoint", "name", "title", "summary");
            appendFields(builder, item, "note", "evidence");
            return builder.length() == 0 ? null : builder.toString();
        }
        return null;
    }

    private static void appendFields(StringBuilder builder, JsonNode item, String... fields) {
        for (String field : fields) {
            JsonNode value = item.get(field);
            if (value != null && value.isTextual() && !value.textValue().isBlank()) {
                if (builder.length() > 0) {
                    builder.append(" — ");
                }
                builder.append(value.textValue().strip());
            }
        }
    }

    private static Map<String, List<String>> roleScopes(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> scopes = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            if (!entry.getKey().isBlank()) {
                JsonNode value = entry.getValue();
                if (value != null && value.isObject()) {
                    String single = textItem(value);
                    scopes.put(entry.getKey(), single == null ? List.of() : List.of(single));
                } else {
                    scopes.put(entry.getKey(), textList(value));
                }
            }
        });
        return Map.copyOf(scopes);
    }

    private record ParsedConclusion(
            String summary,
            List<String> moduleRoots,
            List<String> entryPoints,
            List<String> constraints,
            List<String> risks,
            List<String> evidencePaths,
            Map<String, List<String>> roleScopes) {
    }
}
