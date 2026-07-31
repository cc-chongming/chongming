package ai.cc.chongming.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * [AIREVIEW-PLAN-020#4.1] Verifies the constructor-bound diagnostics flags used by the live runtime.
 *
 * @author wangli
 */
class ReviewDiagnosticsPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DiagnosticsPropertiesConfiguration.class);

    @Test
    void bindsBothDiagnosticsFlagsWhenTheRecordAlsoProvidesACompatibilityConstructor() {
        contextRunner.withPropertyValues(
                        "review.diagnostics.log-startup-failure-stack=true",
                        "review.diagnostics.context-scout-preview-enabled=true")
                .run(context -> {
                    ReviewDiagnosticsProperties properties = context.getBean(ReviewDiagnosticsProperties.class);

                    assertThat(properties.logStartupFailureStack()).isTrue();
                    assertThat(properties.contextScoutPreviewEnabled()).isTrue();
                });
    }

    /**
     * Test-only property binding registration.
     *
     * @author wangli
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewDiagnosticsProperties.class)
    static class DiagnosticsPropertiesConfiguration {
    }
}
