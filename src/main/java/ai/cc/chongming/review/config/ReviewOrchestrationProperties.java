package ai.cc.chongming.review.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounded orchestration settings that prevent an autonomous director from revising indefinitely.
 * [AIREVIEW-PLAN-020#0.5.1] {@code parallelRoleRounds} bounds how many first-round role sessions
 * may be dispatched concurrently after all roles are registered.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.orchestration")
public record ReviewOrchestrationProperties(@Min(1) int maxPlanRevisions, @Min(1) int parallelRoleRounds) {

    public ReviewOrchestrationProperties {
        if (parallelRoleRounds < 1) {
            parallelRoleRounds = 4;
        }
    }
}
