package ai.cc.chongming.review.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-017#3.2] Bounds and redacts runtime text before it enters a browser-visible trace.
 *
 * @author wangli
 */
@Component
public class RuntimeTraceRedactor {

    private static final int MAX_VISIBLE_TEXT_LENGTH = 8_000;
    private static final int MAX_TOOL_TEXT_LENGTH = 2_000;
    private static final int MAX_TOOL_COLLECTION_SIZE = 24;
    private static final int MAX_TOOL_VALUE_DEPTH = 3;
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(password|api[_-]?key|authorization|token)\\s*[=:]\\s*[^\\s,;]+|\\bbearer\\s+\\S+|\\bsk-[A-Za-z0-9_-]+" );
    private static final Pattern JSON_CREDENTIAL = Pattern.compile(
            "(?i)([\\\"']?(?:password|api[_-]?key|authorization|token)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+");
    private static final Pattern SENSITIVE_FIELD_NAME = Pattern.compile(
            "(?i).*(?:password|api[_-]?key|authorization|token).*" );
    private static final Pattern ABSOLUTE_WINDOWS_PATH = Pattern.compile("(?i)[a-z]:\\\\[^\\s`\\\"]+");
    private static final Pattern USER_HOME_PATH = Pattern.compile("(?i)(?:/home/|/users/)[^\\s`\\\"]+");
    private static final Pattern ABSOLUTE_POSIX_PATH = Pattern.compile("(?<![\\w*/])/(?!/)[^\\s`\\\"]+");

    public String redactVisibleText(String value) {
        return redact(value, MAX_VISIBLE_TEXT_LENGTH).value();
    }

    /** Redacts a structured native-tool input while preserving its safe, review-relevant fields. */
    public TracePayload redactToolInput(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return new TracePayload(Map.of(), false);
        }
        MutableTruncation truncation = new MutableTruncation();
        Map<String, Object> sanitized = sanitizeMap(value, 0, truncation);
        return new TracePayload(sanitized, truncation.value);
    }

    /**
     * Redacts textual native-tool output. Binary/data blocks are intentionally excluded by the
     * collector, so the browser only receives bounded text and its explicit truncation state.
     */
    public TracePayload redactToolOutput(String value) {
        RedactedText redacted = redact(value, MAX_TOOL_TEXT_LENGTH);
        if (redacted.value().isBlank()) {
            return new TracePayload(Map.of("summary", "工具未返回文本内容"), redacted.truncated());
        }
        String summary = redacted.value().replaceAll("\\s+", " ").trim();
        if (summary.length() > 240) {
            summary = summary.substring(0, 240) + "…";
        }
        return new TracePayload(Map.of("text", redacted.value(), "summary", summary), redacted.truncated());
    }

    /** Keeps implementation exceptions out of browser-visible SSE and preview status responses. */
    public String redactRuntimeError(Throwable ignored) {
        return "Context Scout 运行失败，详细异常已隐藏。";
    }

    private RedactedText redact(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return new RedactedText("", false);
        }
        String redacted = JSON_CREDENTIAL.matcher(value).replaceAll("$1[REDACTED]");
        redacted = CREDENTIAL.matcher(redacted).replaceAll("[REDACTED]");
        redacted = ABSOLUTE_WINDOWS_PATH.matcher(redacted).replaceAll("[HOST_PATH_REDACTED]");
        redacted = USER_HOME_PATH.matcher(redacted).replaceAll("[HOST_PATH_REDACTED]");
        redacted = ABSOLUTE_POSIX_PATH.matcher(redacted).replaceAll("[HOST_PATH_REDACTED]");
        return redacted.length() <= maxLength
                ? new RedactedText(redacted, false)
                : new RedactedText(redacted.substring(0, maxLength) + "…[已截断]", true);
    }

    private Map<String, Object> sanitizeMap(
            Map<String, Object> source, int depth, MutableTruncation truncation) {
        Map<String, Object> target = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (count++ >= MAX_TOOL_COLLECTION_SIZE) {
                truncation.value = true;
                break;
            }
            RedactedText key = redact(String.valueOf(entry.getKey()), 120);
            truncation.value |= key.truncated();
            target.put(
                    key.value(),
                    SENSITIVE_FIELD_NAME.matcher(String.valueOf(entry.getKey())).matches()
                            ? "[REDACTED]"
                            : sanitizeValue(entry.getValue(), depth + 1, truncation));
        }
        return Collections.unmodifiableMap(target);
    }

    private Object sanitizeValue(Object value, int depth, MutableTruncation truncation) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth > MAX_TOOL_VALUE_DEPTH) {
            truncation.value = true;
            return "[VALUE_DEPTH_TRUNCATED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> normalized.put(String.valueOf(key), nestedValue));
            return sanitizeMap(normalized, depth, truncation);
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                if (index >= MAX_TOOL_COLLECTION_SIZE) {
                    truncation.value = true;
                    break;
                }
                sanitized.add(sanitizeValue(list.get(index), depth + 1, truncation));
            }
            return Collections.unmodifiableList(sanitized);
        }
        RedactedText text = redact(String.valueOf(value), MAX_TOOL_TEXT_LENGTH);
        truncation.value |= text.truncated();
        return text.value();
    }

    /** Browser-safe payload with explicit loss-of-detail signaling. */
    public record TracePayload(Map<String, Object> value, boolean truncated) {}

    private record RedactedText(String value, boolean truncated) {}

    private static final class MutableTruncation {
        private boolean value;
    }
}
