package ai.cc.chongming.review.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the business persistence schema migrates on a real MySQL server.
 *
 * @author wangli
 */
@Testcontainers(disabledWithoutDocker = true)
class ReviewPersistenceMigrationIntegrationTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("chongming")
            .withUsername("review")
            .withPassword("review");

    @Test
    void migratesBusinessTablesWithUtf8mb4Schema() throws SQLException {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            Set<String> tables = readTables(connection.getMetaData(), connection.getCatalog());

            assertThat(tables).contains(
                    "review_request",
                    "review_plan",
                    "evidence_block",
                    "claim",
                    "debate_topic",
                    "review_event",
                    "notification_outbox",
                    "review_report");
        }
    }

    private Set<String> readTables(DatabaseMetaData metadata, String catalog) throws SQLException {
        java.util.HashSet<String> tables = new java.util.HashSet<>();
        try (ResultSet resultSet = metadata.getTables(catalog, null, "%", new String[] {"TABLE"})) {
            while (resultSet.next()) {
                tables.add(resultSet.getString("TABLE_NAME"));
            }
        }
        return tables;
    }
}
