package ai.cc.chongming.review.config;

import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import java.util.EnumSet;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Deployment-tunable, non-final defaults used when a verified P1 opposing Claim remains unresolved.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.gate")
public record ReviewGateProperties(GateResult p1OpposeResult) {

    public ReviewGateProperties {
        if (!EnumSet.of(GateResult.CONDITIONAL, GateResult.RETURN, GateResult.BLOCK, GateResult.HUMAN_REQUIRED)
                .contains(p1OpposeResult)) {
            throw new IllegalArgumentException("p1OpposeResult must be a non-final AI Gate result");
        }
    }
}