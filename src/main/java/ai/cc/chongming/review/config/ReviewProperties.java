package ai.cc.chongming.review.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Review service limits and workspace settings.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review")
public record ReviewProperties(
        @NotBlank String workspaceRoot,
        @Min(1) int maxAgents,
        @Min(1) int maxDebateRounds) {
}
