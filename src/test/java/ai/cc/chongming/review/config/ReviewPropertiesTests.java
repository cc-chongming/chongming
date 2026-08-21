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
                    assertThat(agentScopeProperties.directorMaxIterations()).isEqualTo(48);
                    assertThat(agentScopeProperties.scoutMaxIterations()).isEqualTo(12);
        assertThat(agentScopeProperties.scoutMaxToolCalls()).isEqualTo(16);
                    assertThat(agentScopeProperties.scoutTimeout()).isEqualTo(java.time.Duration.ofSeconds(150));
                    assertThat(agentScopeProperties.debateMaxDirectorWakes()).isEqualTo(24);
                    assertThat(agentScopeProperties.debateConvergenceTimeout()).isEqualTo(java.time.Duration.ofMinutes(20));
                    assertThat(modelGatewayProperties.enabled()).isFalse();
                });
    }

    @Test
    void overridesDirectorMaxIterations() {
        contextRunner.withPropertyValues(baseProperties())
                .withPropertyValues("review.agentscope.director-max-iterations=64")
                .run(context -> assertThat(context.getBean(AgentScopeProperties.class).directorMaxIterations())
                        .isEqualTo(64));
    }

    @Test
    void overridesScoutRuntimeLimits() {
        contextRunner.withPropertyValues(baseProperties())
                .withPropertyValues(
                        "review.agentscope.scout-max-iterations=8",
                        "review.agentscope.scout-max-tool-calls=9",
                        "review.agentscope.scout-timeout=PT25S")
                .run(context -> {
                    AgentScopeProperties properties = context.getBean(AgentScopeProperties.class);
                    assertThat(properties.scoutMaxIterations()).isEqualTo(8);
                    assertThat(properties.scoutMaxToolCalls()).isEqualTo(9);
                    assertThat(properties.scoutTimeout()).isEqualTo(java.time.Duration.ofSeconds(25));
                });
    }

    @Test
    void bindsEnabledGatewayWithDirectConfigurationCredential() {
        contextRunner.withPropertyValues(baseProperties())
                .withPropertyValues(
                        "review.model-gateway.enabled=true",
                        "review.model-gateway.base-url=https://example.invalid/v1",
                        "review.model-gateway.model-name=test-model",
                        "review.model-gateway.api-key=test-key")
                .run(context -> {
                    ModelGatewayProperties properties = context.getBean(ModelGatewayProperties.class);

                    assertThat(properties.apiKey()).isEqualTo("test-key");
                });
    }

    @Test
    void rejectsEnabledGatewayWithoutDirectConfigurationCredential() {
        contextRunner.withPropertyValues(baseProperties())
                .withPropertyValues(
                        "review.model-gateway.enabled=true",
                        "review.model-gateway.base-url=https://example.invalid/v1",
                        "review.model-gateway.model-name=test-model",
                        "review.model-gateway.api-key=")
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
                "review.model-gateway.api-key=test-key"
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
