package ai.cc.chongming.review.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounded orchestration settings that prevent an autonomous director from revising indefinitely.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.orchestration")
public record ReviewOrchestrationProperties(@Min(1) int maxPlanRevisions) {
}
