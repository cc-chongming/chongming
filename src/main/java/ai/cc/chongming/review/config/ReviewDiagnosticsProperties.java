package ai.cc.chongming.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Controls local-only diagnostics that must remain disabled in shared environments.
 *
 * @author wangli
 */
@ConfigurationProperties(prefix = "review.diagnostics")
public record ReviewDiagnosticsProperties(
        boolean logStartupFailureStack,
        boolean contextScoutPreviewEnabled) {

    @ConstructorBinding
    public ReviewDiagnosticsProperties {
    }

    public ReviewDiagnosticsProperties(boolean logStartupFailureStack) {
        this(logStartupFailureStack, false);
    }
}
