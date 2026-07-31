package ai.cc.chongming.review.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

/**
 * AgentScope runtime persistence settings.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.agentscope")
public record AgentScopeProperties(
        boolean persistSession,
        @NotBlank String stateHome,
        @DefaultValue("48") @Min(1) int directorMaxIterations,
        @DefaultValue("12") @Min(1) int scoutMaxIterations,
        @DefaultValue("9") @Min(1) int scoutMaxToolCalls,
        @DefaultValue("PT90S") Duration scoutTimeout) {

    @ConstructorBinding
    public AgentScopeProperties {
    }

    public AgentScopeProperties(boolean persistSession, String stateHome) {
        this(persistSession, stateHome, 48, 12, 9, Duration.ofSeconds(90));
    }

    public AgentScopeProperties(boolean persistSession, String stateHome, int directorMaxIterations) {
        this(persistSession, stateHome, directorMaxIterations, 12, 9, Duration.ofSeconds(90));
    }
}
