package ai.cc.chongming.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests non-sensitive logical profile binding independently from real model credentials.
 * <p>
 * [AIREVIEW-PLAN-023#8]
 *
 * @author zyj
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

    @Test
    void defaultsStreamEnabledToTrueWhenPropertyIsAbsent() {
        ModelProfilesProperties properties = bindProfile(Map.of());

        assertThat(properties.profiles().get("director").streamEnabled()).isTrue();
    }

    @Test
    void bindsExplicitlyDisabledStreaming() {
        ModelProfilesProperties properties = bindProfile(Map.of(
                "review.model-gateway.profiles.director.stream-enabled", "false"));

        assertThat(properties.profiles().get("director").streamEnabled()).isFalse();
    }

    private ModelProfilesProperties bindProfile(Map<String, String> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("review.model-gateway.profiles.director.provider", "openai-compatible");
        values.put("review.model-gateway.profiles.director.model-name", "director-model");
        values.put("review.model-gateway.profiles.director.temperature", "0.2");
        values.put("review.model-gateway.profiles.director.timeout", "PT45S");
        values.put("review.model-gateway.profiles.director.max-tokens", "2048");
        values.put("review.model-gateway.profiles.director.retry.max-retries", "2");
        values.put("review.model-gateway.profiles.director.retry.initial-backoff", "PT0.2S");
        values.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("review.model-gateway", Bindable.of(ModelProfilesProperties.class))
                .orElseThrow(() -> new AssertionError("model profile properties must bind"));
    }

    /**
     * Test-only configuration-properties registration.
     *
     * @author zyj
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModelProfilesProperties.class)
    static class PropertiesConfiguration {
    }
}
