package ai.cc.chongming.review.config;

import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes stateless protocol services to application-layer orchestration without coupling domain classes to Spring.
 *
 * @author wangli
 */
@Configuration(proxyBeanMethods = false)
public class ReviewProtocolConfiguration {

    @Bean
    ReviewProtocolGuard reviewProtocolGuard() {
        return new ReviewProtocolGuard();
    }

    @Bean
    ReviewStateMachine reviewStateMachine() {
        return new ReviewStateMachine();
    }
}
