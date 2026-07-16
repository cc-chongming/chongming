package ai.cc.chongming.review.config;

import ai.cc.chongming.review.domain.gate.GatePolicy;
import ai.cc.chongming.review.domain.protocol.DebateStateMachine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the stateless bounded debate protocol without coupling domain code to Spring.
 *
 * @author wangli
 */
@Configuration(proxyBeanMethods = false)
public class DebateProtocolConfiguration {

    @Bean
    DebateStateMachine debateStateMachine() {
        return new DebateStateMachine();
    }

    @Bean
    GatePolicy gatePolicy(ReviewGateProperties properties) {
        return new GatePolicy(properties.p1OpposeResult());
    }
}
