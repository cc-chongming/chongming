package ai.cc.chongming.auth.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code review.auth.*} section. The JWT secret is intentionally not validated as
 * non-blank here: the default placeholder is empty and {@link ai.cc.chongming.auth.application.JwtTokenService}
 * fails fast with an actionable message when it is missing or too short, keeping test profiles
 * free of property-binding failures.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.auth")
public record AuthProperties(
        boolean enabled,
        String jwtSecret,
        @NotNull Duration tokenTtl) {
}
