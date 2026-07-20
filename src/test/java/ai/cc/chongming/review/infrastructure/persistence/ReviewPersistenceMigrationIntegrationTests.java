package ai.cc.chongming.review.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
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
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:5.6")
            .withDatabaseName("chongming")
            .withUsername("review")
            .withPassword("review");

    @Container
    static final MySQLContainer<?> MYSQL8 = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("chongming")
            .withUsername("review")
            .withPassword("review");

    @Test
    void migratesBusinessTablesWithLongTextPayloadsOnMysql56() throws SQLException {
        MysqlDataSource dataSource = dataSource(MYSQL);

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
                    "review_report",
                    "chongming_agentscope_state",
                    "chongming_agentscope_workspace");
            Map<String, String> expectedLongTextColumns = Map.of(
                    "review_plan.plan_json", "LONGTEXT",
                    "repository_snapshot.manifest_json", "LONGTEXT",
                    "debate_turn.evidence_summary", "LONGTEXT",
                    "review_event.payload_json", "LONGTEXT",
                    "audit_event.metadata_json", "LONGTEXT",
                    "notification_outbox.payload_json", "LONGTEXT");
            for (Map.Entry<String, String> expectedColumn : expectedLongTextColumns.entrySet()) {
                String[] tableAndColumn = expectedColumn.getKey().split("\\.", 2);
                assertThat(readColumnType(connection.getMetaData(), connection.getCatalog(), tableAndColumn[0], tableAndColumn[1]))
                        .isEqualTo(expectedColumn.getValue());
            }
            assertThat(readColumnSize(connection.getMetaData(), connection.getCatalog(), "role_activation", "session_id"))
                    .isEqualTo(255);
        }
    }

    @Test
    void convertsExistingJsonPayloadsToLongTextWithoutLosingSerializedContent() throws SQLException {
        MysqlDataSource dataSource = dataSource(MYSQL8);
        Map<String, String> serializedPayloads = serializedPayloadColumns();
        try (Connection connection = dataSource.getConnection()) {
            createLegacyJsonPayloadTables(connection, serializedPayloads);
            insertLegacyJsonPayloads(connection, serializedPayloads);
        }

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("7")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            for (Map.Entry<String, String> payloadColumn : serializedPayloads.entrySet()) {
                String[] tableAndColumn = payloadColumn.getKey().split("\\.", 2);
                assertThat(readColumnType(connection.getMetaData(), connection.getCatalog(), tableAndColumn[0], tableAndColumn[1]))
                        .isEqualTo("LONGTEXT");
                assertThat(readStoredPayload(connection, tableAndColumn[0], tableAndColumn[1]))
                        .isEqualTo(payloadColumn.getValue());
            }
        }
    }

    private String readColumnType(DatabaseMetaData metadata, String catalog, String tableName, String columnName)
            throws SQLException {
        try (ResultSet resultSet = metadata.getColumns(catalog, null, tableName, columnName)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString("TYPE_NAME").toUpperCase(Locale.ROOT);
        }
    }

    private int readColumnSize(DatabaseMetaData metadata, String catalog, String tableName, String columnName)
            throws SQLException {
        try (ResultSet resultSet = metadata.getColumns(catalog, null, tableName, columnName)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt("COLUMN_SIZE");
        }
    }

    private MysqlDataSource dataSource(MySQLContainer<?> mysql) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(mysql.getJdbcUrl());
        dataSource.setUser(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        return dataSource;
    }

    private Map<String, String> serializedPayloadColumns() {
        return Map.of(
                "review_plan.plan_json", "{\"plan\":\"alpha\"}",
                "repository_snapshot.manifest_json", "{\"revision\":\"r1\"}",
                "debate_turn.evidence_summary", "{\"evidence\":\"e1\"}",
                "review_event.payload_json", "{\"event\":\"created\"}",
                "audit_event.metadata_json", "{\"actor\":\"reviewer\"}",
                "notification_outbox.payload_json", "{\"channel\":\"local\"}");
    }

    private void createLegacyJsonPayloadTables(Connection connection, Map<String, String> payloadColumns) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String tableAndColumn : payloadColumns.keySet()) {
                String[] parts = tableAndColumn.split("\\.", 2);
                statement.execute("CREATE TABLE " + parts[0] + " (id BIGINT NOT NULL PRIMARY KEY, " + parts[1] + " JSON NULL)");
            }
        }
    }

    private void insertLegacyJsonPayloads(Connection connection, Map<String, String> payloadColumns) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, String> payloadColumn : payloadColumns.entrySet()) {
                String[] parts = payloadColumn.getKey().split("\\.", 2);
                statement.execute("INSERT INTO " + parts[0] + " (id, " + parts[1] + ") VALUES (1, '"
                        + payloadColumn.getValue().replace("'", "''") + "')");
            }
        }
    }

    private String readStoredPayload(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT " + columnName + " FROM " + tableName + " WHERE id = 1")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
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
