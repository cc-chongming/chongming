package ai.cc.chongming.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration binding tests for the review runtime baseline.
 *
 * @author wangli
 */
class ReviewPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsNonSensitiveRuntimeSettings() {
        contextRunner.withPropertyValues(baseProperties())
                .run(context -> {
                    ReviewProperties reviewProperties = context.getBean(ReviewProperties.class);
                    AgentScopeProperties agentScopeProperties = context.getBean(AgentScopeProperties.class);
                    ModelGatewayProperties modelGatewayProperties = context.getBean(ModelGatewayProperties.class);

                    assertThat(reviewProperties.workspaceRoot()).isEqualTo(".agentscope/workspace");
                    assertThat(reviewProperties.maxAgents()).isEqualTo(8);
                    assertThat(agentScopeProperties.persistSession()).isTrue();
                    assertThat(modelGatewayProperties.enabled()).isFalse();
                });
    }

    @Test
    void rejectsEnabledGatewayWithoutEnvironmentCredential() {
        contextRunner.withPropertyValues(baseProperties())
                .withPropertyValues(
                        "review.model-gateway.enabled=true",
                        "review.model-gateway.base-url=https://example.invalid/v1",
                        "review.model-gateway.model-name=test-model",
                        "review.model-gateway.api-key-environment-variable=CHONGMING_MISSING_API_KEY")
                .run(context -> assertThat(context).hasFailed());
    }

    private String[] baseProperties() {
        return new String[]{
                "review.workspace-root=.agentscope/workspace",
                "review.max-agents=8",
                "review.max-debate-rounds=2",
                "review.agentscope.persist-session=true",
                "review.agentscope.state-home=.agentscope/state",
                "review.model-gateway.enabled=false",
                "review.model-gateway.api-key-environment-variable=CHONGMING_TEST_API_KEY"
        };
    }

    /**
     * Test-only configuration properties registration.
     *
     * @author wangli
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ReviewProperties.class, AgentScopeProperties.class, ModelGatewayProperties.class})
    static class PropertiesConfiguration {
    }
}
