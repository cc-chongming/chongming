package ai.cc.chongming.auth.config;

import ai.cc.chongming.auth.config.AuthStartupContract.AssemblyDecision;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Assembles the authentication module according to the startup contract: the switch stays on
 * by default, but a missing or too short JWT secret only fails fast when the operator
 * explicitly enabled authentication; otherwise the module is skipped with a warning so
 * unconfigured environments keep starting.
 *
 * @author wangli
 */
public class AuthModuleEnabledCondition extends SpringBootCondition {

    private static final Log logger = LogFactory.getLog(AuthModuleEnabledCondition.class);

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        Boolean enabledFlag = environment.getProperty(AuthStartupContract.ENABLED_PROPERTY, Boolean.class);
        String jwtSecret = environment.getProperty(AuthStartupContract.SECRET_PROPERTY);
        boolean explicitlyEnabled = AuthStartupContract.resolveExplicitlyEnabled(environment);
        AssemblyDecision decision = AuthStartupContract.decideAssembly(enabledFlag, explicitlyEnabled, jwtSecret);
        return switch (decision) {
            case ENABLED -> ConditionOutcome.match("review.auth assembled with a usable JWT secret");
            case DISABLED -> {
                if (!Boolean.FALSE.equals(enabledFlag)) {
                    logger.warn("review.auth.jwt-secret is missing or shorter than "
                            + AuthStartupContract.MIN_SECRET_BYTES
                            + " bytes and review.auth.enabled was not explicitly set to true; "
                            + "the authentication module is disabled. Provide REVIEW_AUTH_JWT_SECRET to enable it.");
                }
                yield ConditionOutcome.noMatch("review.auth disabled by the startup contract");
            }
            case FAIL_FAST -> throw new IllegalStateException(
                    "review.auth.enabled is explicitly true but review.auth.jwt-secret is missing or shorter than "
                            + AuthStartupContract.MIN_SECRET_BYTES
                            + " bytes; provide REVIEW_AUTH_JWT_SECRET (at least 32 characters) or disable review.auth");
        };
    }
}
