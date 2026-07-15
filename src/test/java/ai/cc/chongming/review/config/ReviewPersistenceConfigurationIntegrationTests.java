package ai.cc.chongming.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.DistributedStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the shared MySQL wiring for business data and AgentScope runtime state.
 *
 * @author wangli
 */
@Testcontainers(disabledWithoutDocker = true)
class ReviewPersistenceConfigurationIntegrationTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("chongming")
            .withUsername("review")
            .withPassword("review");

    private final ReviewPersistenceConfiguration configuration = new ReviewPersistenceConfiguration();

    @Test
    void sharesOneDatasourceBetweenFlywayAndConfiguredAgentScopeStores() {
        ReviewPersistenceProperties properties = new ReviewPersistenceProperties(
                true,
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword(),
                3,
                new ReviewPersistenceProperties.AgentScopeMysqlProperties(
                        "chongming",
                        "chongming_agentscope_state",
                        "chongming_agentscope_workspace",
                        "chongming_agentscope_snapshot",
                        "chongming:agentscope:lock:",
                        true));
        HikariDataSource dataSource = configuration.reviewDataSource(properties);

        try {
            configuration.reviewFlyway(dataSource);
            DistributedStore store = configuration.reviewDistributedStore(dataSource, properties);
            AgentState expected = AgentState.builder()
                    .userId("user-001")
                    .sessionId("review-001")
                    .summary("review runtime persisted")
                    .build();

            store.agentStateStore().save("user-001", "review-001", "runtime", expected);
            AgentState actual = store.agentStateStore()
                    .get("user-001", "review-001", "runtime", AgentState.class)
                    .orElseThrow();

            assertThat(actual.getSummary()).isEqualTo("review runtime persisted");
            assertThat(store.baseStore()).isNotNull();
            assertThat(store.sandboxExecutionGuard()).isNotNull();
        } finally {
            dataSource.close();
        }
    }
}
