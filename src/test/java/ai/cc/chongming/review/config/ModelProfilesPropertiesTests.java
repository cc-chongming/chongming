package ai.cc.chongming.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests non-sensitive logical profile binding independently from real model credentials.
 *
 * @author wangli
 */
class ModelProfilesPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsLogicalProfilesAndRetrySettings() {
        contextRunner.withPropertyValues(
                        "review.model-gateway.profiles.director.provider=openai-compatible",
                        "review.model-gateway.profiles.director.model-name=director-model",
                        "review.model-gateway.profiles.director.temperature=0.2",
                        "review.model-gateway.profiles.director.timeout=PT45S",
                        "review.model-gateway.profiles.director.max-tokens=2048",
                        "review.model-gateway.profiles.director.retry.max-retries=2",
                        "review.model-gateway.profiles.director.retry.initial-backoff=PT0.2S")
                .run(context -> {
                    ModelProfilesProperties properties = context.getBean(ModelProfilesProperties.class);
                    var director = properties.profiles().get("director");

                    assertThat(director.provider()).isEqualTo("openai-compatible");
                    assertThat(director.timeout()).hasSeconds(45);
                    assertThat(director.retry().maxRetries()).isEqualTo(2);
                });
    }

    /**
     * Test-only configuration-properties registration.
     *
     * @author wangli
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModelProfilesProperties.class)
    static class PropertiesConfiguration {
    }
}
