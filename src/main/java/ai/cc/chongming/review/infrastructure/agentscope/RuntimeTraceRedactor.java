package ai.cc.chongming.review.infrastructure.agentscope;

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
    private static final Pattern ABSOLUTE_WINDOWS_PATH = Pattern.compile("(?i)[a-z]:\\\\[^\\s`\\\"]+");
    private static final Pattern USER_HOME_PATH = Pattern.compile("(?i)(?:/home/|/users/)[^\\s`\\\"]+");

    public String redactVisibleText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = CREDENTIAL.matcher(value).replaceAll("[REDACTED]");
        redacted = ABSOLUTE_WINDOWS_PATH.matcher(redacted).replaceAll("[HOST_PATH_REDACTED]");
        redacted = USER_HOME_PATH.matcher(redacted).replaceAll("[HOST_PATH_REDACTED]");
        return redacted.length() <= MAX_VISIBLE_TEXT_LENGTH
                ? redacted
                : redacted.substring(0, MAX_VISIBLE_TEXT_LENGTH) + "…[已截断]";
    }
}
