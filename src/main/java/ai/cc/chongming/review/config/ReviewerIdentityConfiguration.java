package ai.cc.chongming.review.config;

import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.Permission;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Set;

/**
 * [AIREVIEW-PLAN-011#1.2][AIREVIEW-PLAN-011#1.7] Limits the fallback reviewer identity to local/demo/test profiles.
 *
 * @author wangli
 */
@Configuration(proxyBeanMethods = false)
public class ReviewerIdentityConfiguration {

    @Bean
    @Profile({"local", "demo", "test"})
    ReviewerIdentityProvider localDemoReviewerIdentityProvider() {
        return () -> new ReviewerIdentity("local-demo-reviewer", Set.of(Permission.REVIEW, Permission.OVERRIDE));
    }

    @Bean
    @Profile("!local & !demo & !test")
    ReviewerIdentityProvider deniedReviewerIdentityProvider() {
        return () -> new ReviewerIdentity("unauthenticated", Set.of());
    }
}
