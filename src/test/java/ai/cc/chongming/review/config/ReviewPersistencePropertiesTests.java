package ai.cc.chongming.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Tests for optional persistence property binding.
 *
 * @author wangli
 */
class ReviewPersistencePropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PersistencePropertiesConfiguration.class)
            .withPropertyValues(
                    "review.persistence.enabled=false",
                    "review.persistence.jdbc-url=jdbc:mysql://localhost:3306/chongming_placeholder",
                    "review.persistence.username=chongming_placeholder",
                    "review.persistence.password=",
                    "review.persistence.maximum-pool-size=8",
                    "review.persistence.agentscope.database-name=chongming_placeholder",
                    "review.persistence.agentscope.state-table-name=chongming_agentscope_state",
                    "review.persistence.agentscope.workspace-table-name=chongming_agentscope_workspace",
                    "review.persistence.agentscope.snapshot-table-name=chongming_agentscope_snapshot",
                    "review.persistence.agentscope.lock-key-prefix=chongming:agentscope:lock:",
                    "review.persistence.agentscope.initialize-schema=false");

    @Test
    void bindsDisabledPlaceholderConfigurationWithoutCreatingDataSource() {
        contextRunner.run(context -> {
            ReviewPersistenceProperties properties = context.getBean(ReviewPersistenceProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.jdbcUrl()).contains("chongming_placeholder");
            assertThat(properties.agentscope().initializeSchema()).isFalse();
            assertThat(context).doesNotHaveBean("reviewDataSource");
        });
    }

    /**
     * Test-only property binding configuration.
     *
     * @author wangli
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewPersistenceProperties.class)
    @Import(ReviewPersistenceConfiguration.class)
    static class PersistencePropertiesConfiguration {
    }
}
