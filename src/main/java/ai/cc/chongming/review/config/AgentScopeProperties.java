package ai.cc.chongming.review.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
        @DefaultValue("48") @Min(1) int directorMaxIterations) {

    @ConstructorBinding
    public AgentScopeProperties {
    }

    public AgentScopeProperties(boolean persistSession, String stateHome) {
        this(persistSession, stateHome, 48);
    }
}
