package ai.cc.chongming.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls local-only diagnostics that must remain disabled in shared environments.
 *
 * @author wangli
 */
@ConfigurationProperties(prefix = "review.diagnostics")
public record ReviewDiagnosticsProperties(
        boolean logStartupFailureStack,
        boolean contextScoutPreviewEnabled) {

    public ReviewDiagnosticsProperties(boolean logStartupFailureStack) {
        this(logStartupFailureStack, false);
    }
}
