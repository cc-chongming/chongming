package ai.cc.chongming.review.compatibility;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies MySQL-backed AgentScope state round-tripping against a real MySQL server.
 *
 * @author wangli
 */
@Testcontainers(disabledWithoutDocker = true)
class MysqlAgentStateCompatibilityTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agentscope")
            .withUsername("review")
            .withPassword("review");

    @Test
    void mysqlStateStoreRoundTripsAgentState() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());

        MysqlAgentStateStore stateStore = new MysqlAgentStateStore(
                dataSource, "agentscope", "chongming_agent_state", true);
        try {
            AgentState expected = AgentState.builder()
                    .userId("user-001")
                    .sessionId("review-001")
                    .summary("requirements review in progress")
                    .build();

            stateStore.save("user-001", "review-001", "agent-state", expected);
            AgentState actual = stateStore.get("user-001", "review-001", "agent-state", AgentState.class)
                    .orElseThrow();

            assertThat(actual.getUserId()).isEqualTo(expected.getUserId());
            assertThat(actual.getSessionId()).isEqualTo(expected.getSessionId());
            assertThat(actual.getSummary()).isEqualTo(expected.getSummary());
        } finally {
            stateStore.close();
        }
    }
}
