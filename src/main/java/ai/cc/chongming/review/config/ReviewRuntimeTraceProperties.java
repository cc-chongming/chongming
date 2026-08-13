package ai.cc.chongming.review.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * [AIREVIEW-PLAN-022#5.4] Retention and enablement of the durable AG-UI runtime trace.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.runtime-trace.persistence")
public record ReviewRuntimeTraceProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20000") @Min(1) int maxEvents) {

    @ConstructorBinding
    public ReviewRuntimeTraceProperties {
    }

    public ReviewRuntimeTraceProperties(boolean enabled) {
        this(enabled, 20000);
    }
}
