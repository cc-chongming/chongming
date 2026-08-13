package ai.cc.chongming.auth.config;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Startup contract decisions for the authentication module, kept as pure functions so the
 * downgrade semantics are unit-testable without a Spring context. Contract: a missing or too
 * short {@code review.auth.jwt-secret} only fails fast when {@code review.auth.enabled} was
 * explicitly set to {@code true} by the operator; otherwise the module degrades to disabled so
 * existing environments keep starting.
 *
 * @author wangli
 */
public final class AuthStartupContract {

    /** HS256 mandates a key of at least 256 bits. */
    public static final int MIN_SECRET_BYTES = 32;

    /** Property carrying the module switch; the packaged default is true. */
    public static final String ENABLED_PROPERTY = "review.auth.enabled";

    /** Environment variable mirror of the module switch. */
    public static final String ENABLED_ENVIRONMENT_VARIABLE = "REVIEW_AUTH_ENABLED";

    /** Property carrying the signing secret; the packaged default is empty. */
    public static final String SECRET_PROPERTY = "review.auth.jwt-secret";

    private AuthStartupContract() {
    }

    /** Outcome of the startup decision. */
    public enum AssemblyDecision {
        /** Wire the full authentication module. */
        ENABLED,
        /** Skip the authentication module; the application starts without it. */
        DISABLED,
        /** The operator explicitly enabled auth without a usable secret; refuse to start. */
        FAIL_FAST
    }

    /**
     * Checks the HS256 key requirement: present, non-blank and at least
     * {@link #MIN_SECRET_BYTES} UTF-8 bytes long.
     *
     * @param jwtSecret configured signing secret
     * @return true when the secret can back HS256 signatures
     */
    public static boolean jwtSecretUsable(String jwtSecret) {
        return jwtSecret != null && !jwtSecret.isBlank()
                && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
    }

    /**
     * Decides how the authentication module should assemble.
     *
     * @param enabledFlag       bound {@code review.auth.enabled} value (null when absent)
     * @param explicitlyEnabled true when the operator set the switch outside packaged defaults
     * @param jwtSecret         configured signing secret
     * @return assembly decision
     */
    public static AssemblyDecision decideAssembly(Boolean enabledFlag, boolean explicitlyEnabled, String jwtSecret) {
        if (Boolean.FALSE.equals(enabledFlag)) {
            return AssemblyDecision.DISABLED;
        }
        if (jwtSecretUsable(jwtSecret)) {
            return AssemblyDecision.ENABLED;
        }
        return explicitlyEnabled ? AssemblyDecision.FAIL_FAST : AssemblyDecision.DISABLED;
    }

    /**
     * Detects whether the module switch was set outside the packaged configuration defaults.
     * The bundled {@code application.yml} carries a placeholder default, so a value coming from
     * it alone is not an explicit operator choice; system properties, environment variables,
     * command-line arguments and profile-specific configuration files are.
     *
     * @param environment active Spring environment
     * @return true when an operator-visible source sets the switch to true
     */
    public static boolean resolveExplicitlyEnabled(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            String value = environment.getProperty(ENABLED_PROPERTY);
            return value != null && Boolean.parseBoolean(value);
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (isPackagedDefaultSource(source.getName())) {
                continue;
            }
            Object value = source.getProperty(ENABLED_PROPERTY);
            if (value == null) {
                value = source.getProperty(ENABLED_ENVIRONMENT_VARIABLE);
            }
            if (value != null) {
                return Boolean.parseBoolean(String.valueOf(value));
            }
        }
        return false;
    }

    private static boolean isPackagedDefaultSource(String sourceName) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        return name.contains("application.yml") || name.contains("application.yaml")
                || name.contains("application.properties");
    }
}
