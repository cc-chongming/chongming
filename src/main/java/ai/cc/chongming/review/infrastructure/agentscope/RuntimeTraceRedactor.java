package ai.cc.chongming.review.infrastructure.agentscope;

import java.util.Collections;
import java.util.LinkedHashMap;
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
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(password|api[_-]?key|authorization|token)\\s*[=:]\\s*[^\\s,;]+|\\bbearer\\s+\\S+|\\bsk-[A-Za-z0-9_-]+" );
    private static final Pattern JSON_CREDENTIAL = Pattern.compile(
            "(?i)([\\\"']?(?:password|api[_-]?key|authorization|token)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+");
    private static final Pattern ABSOLUTE_WINDOWS_PATH = Pattern.compile("(?i)[a-z]:\\\\[^\\s`\\\"]+");
    private static final Pattern USER_HOME_PATH = Pattern.compile("(?i)(?:/home/|/users/)[^\\s`\\\"]+");
    private static final Pattern ABSOLUTE_POSIX_PATH = Pattern.compile("(?<![\\w*/])/(?!/)[^\\s`\\\"]+");

    public String redactVisibleText(String value) {
        return redact(value, MAX_VISIBLE_TEXT_LENGTH).value();
    }

    /**
     * Keeps the complete native-tool input in the local runtime transcript. This debug surface is
     * intentionally not a redacted audit projection.
     */
    public TracePayload rawToolInput(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return new TracePayload(Map.of(), false);
        }
        return new TracePayload(Collections.unmodifiableMap(new LinkedHashMap<>(value)), false);
    }

    /** Keeps the complete native-tool text result in the local runtime transcript. */
    public TracePayload rawToolOutput(String value) {
        if (value == null || value.isBlank()) {
            return new TracePayload(Map.of("summary", "工具未返回文本内容"), false);
        }
        String summary = value.replaceAll("\\s+", " ").trim();
        if (summary.length() > 240) {
            summary = summary.substring(0, 240) + "…";
        }
        return new TracePayload(Map.of("text", value, "summary", summary), false);
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

    /** Browser-safe payload with explicit loss-of-detail signaling. */
    public record TracePayload(Map<String, Object> value, boolean truncated) {}

    private record RedactedText(String value, boolean truncated) {}

}
