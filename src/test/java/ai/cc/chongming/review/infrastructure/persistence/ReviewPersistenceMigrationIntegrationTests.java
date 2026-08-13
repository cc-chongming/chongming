package ai.cc.chongming.review.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysql.cj.jdbc.MysqlDataSource;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewReportMapper;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPlatformProjectionMapper;
import ai.cc.chongming.review.infrastructure.persistence.mapper.RuntimeTracePersistenceMapper;
import java.time.LocalDateTime;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [AIREVIEW-PLAN-023#5] Verifies that the business persistence schema migrates on a real MySQL server.
 *
 * @author zyj
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
                    "requirement",
                    "chongming_agentscope_state",
                    "chongming_agentscope_workspace",
                    "runtime_trace_event",
                    "context_scout_conclusion",
                    "requirement_review_launch_command",
                    "review_assessment",
                    "review_dispatch_command",
                    "users");
            Map<String, String> expectedLongTextColumns = Map.of(
                    "review_plan.plan_json", "LONGTEXT",
                    "repository_snapshot.manifest_json", "LONGTEXT",
                    "debate_turn.evidence_summary", "LONGTEXT",
                    "review_event.payload_json", "LONGTEXT",
                    "audit_event.metadata_json", "LONGTEXT",
                    "notification_outbox.payload_json", "LONGTEXT",
                    "context_scout_conclusion.module_roots_json", "LONGTEXT",
                    "context_scout_conclusion.raw_public_result", "LONGTEXT");
            for (Map.Entry<String, String> expectedColumn : expectedLongTextColumns.entrySet()) {
                String[] tableAndColumn = expectedColumn.getKey().split("\\.", 2);
                assertThat(readColumnType(connection.getMetaData(), connection.getCatalog(), tableAndColumn[0], tableAndColumn[1]))
                        .isEqualTo(expectedColumn.getValue());
            }
            assertThat(readColumnSize(connection.getMetaData(), connection.getCatalog(), "role_activation", "session_id"))
                    .isEqualTo(255);
            assertThat(readColumnType(connection.getMetaData(), connection.getCatalog(), "requirement", "description_md"))
                    .isEqualTo("MEDIUMTEXT");
            assertThat(readColumnType(connection.getMetaData(), connection.getCatalog(), "review_request", "requirement_id"))
                    .isEqualTo("CHAR");
            assertThat(readIndexColumns(connection.getMetaData(), connection.getCatalog(), "review_event", "idx_review_event_occurred_at"))
                    .containsExactly("OCCURRED_AT");
            assertThat(readIndexColumns(connection.getMetaData(), connection.getCatalog(), "review_event", "idx_review_event_recent_activity"))
                    .containsExactly("OCCURRED_AT", "REVIEW_ID", "EVENT_SEQUENCE");
            // [AIREVIEW-PLAN-024#方案5] assessment identity is the (review, attempt, role, checkpoint)
            // composite primary key; dispatch commands are unique on command_id plus idempotency_key.
            assertThat(readPrimaryKeyColumns(connection.getMetaData(), connection.getCatalog(), "review_assessment"))
                    .containsExactly("REVIEW_ID", "ATTEMPT_NO", "ROLE_TYPE", "CHECKPOINT_KEY");
            assertThat(readPrimaryKeyColumns(connection.getMetaData(), connection.getCatalog(), "review_dispatch_command"))
                    .containsExactly("COMMAND_ID");
            assertThat(readIndexColumns(connection.getMetaData(), connection.getCatalog(), "review_assessment", "idx_review_assessment_attempt"))
                    .containsExactly("REVIEW_ID", "ATTEMPT_NO");
            assertThat(readIndexColumns(connection.getMetaData(), connection.getCatalog(), "review_dispatch_command", "uk_review_dispatch_idempotency"))
                    .containsExactly("IDEMPOTENCY_KEY");
            // [AIREVIEW-PLAN-025#1] V20 creates the users table with a unique username index
            // and seeds the built-in administrator account.
            assertThat(readColumnType(connection.getMetaData(), connection.getCatalog(), "users", "password_hash"))
                    .isEqualTo("VARCHAR");
            assertThat(readColumnSize(connection.getMetaData(), connection.getCatalog(), "users", "password_hash"))
                    .isEqualTo(255);
            assertThat(readIndexColumns(connection.getMetaData(), connection.getCatalog(), "users", "uk_users_username"))
                    .containsExactly("USERNAME");
            try (Statement statement = connection.createStatement();
                    ResultSet adminRows = statement.executeQuery(
                            "SELECT COUNT(*) FROM users WHERE username='admin' AND role='ADMIN'")) {
                assertThat(adminRows.next()).isTrue();
                assertThat(adminRows.getLong(1)).isEqualTo(1L);
            }
        }
    }

    @Test
    void convertsExistingJsonPayloadsToLongTextWithoutLosingSerializedContent() throws Exception {
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
                // MySQL JSON columns reformat whitespace on store, so only semantic equality is guaranteed.
                assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(readStoredPayload(connection, tableAndColumn[0], tableAndColumn[1])))
                        .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadColumn.getValue()));
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

    @Test
    void mapsAllPersistentReportReadsThroughTheActualMybatisMapper() throws SQLException {
        MysqlDataSource dataSource = dataSource(MYSQL);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        String reviewId = java.util.UUID.randomUUID().toString();
        String reportId = java.util.UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO review_request "
                    + "(review_id, request_id, submitter_id, stage, input_idempotency_key, current_attempt_no, version) VALUES ('"
                    + reviewId + "', 'request-" + reviewId + "', 'reviewer', 'COMPLETED', 'idempotency-" + reviewId
                    + "', 1, 0)");
        }
        SqlSessionFactory sessionFactory = reportMapperSessionFactory(dataSource);
        try (SqlSession session = sessionFactory.openSession(true)) {
            ReviewReportMapper mapper = session.getMapper(ReviewReportMapper.class);
            mapper.insert(new ReviewReportMapper.ReportRow(
                    reportId, reviewId, 1, 1L, 1L, "a".repeat(64), "{\"summary\":\"mapped\"}",
                    "# mapped", LocalDateTime.of(2026, 8, 1, 16, 0)));

            assertThat(mapper.findLatest(reviewId)).extracting(ReviewReportMapper.ReportRow::attemptNo).isEqualTo(1);
            assertThat(mapper.findVersion(reviewId, 1L)).extracting(ReviewReportMapper.ReportRow::attemptNo).isEqualTo(1);
            assertThat(mapper.findVersions(reviewId)).singleElement()
                    .extracting(ReviewReportMapper.ReportRow::attemptNo).isEqualTo(1);
            assertThat(mapper.findLatestAcrossReviews(10)).anySatisfy(report -> {
                assertThat(report.reportId()).isEqualTo(reportId);
                assertThat(report.attemptNo()).isEqualTo(1);
            });
            assertThat(mapper.findLatestMetadataPage(0L, 10)).anySatisfy(report -> {
                assertThat(report.reviewId()).isEqualTo(reviewId);
                assertThat(report.reportVersion()).isEqualTo(1L);
            });
            assertThat(mapper.countLatestMetadata()).isGreaterThanOrEqualTo(1L);
        }
    }

    @Test
    void projectsAReviewRootBeforeItHasAnyRuntimeEvent() throws SQLException {
        MysqlDataSource dataSource = dataSource(MYSQL);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        String reviewId = java.util.UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO review_request "
                    + "(review_id, request_id, submitter_id, stage, input_idempotency_key, current_attempt_no, version) VALUES ('"
                    + reviewId + "', 'request-" + reviewId + "', 'reviewer', 'PENDING', 'idempotency-" + reviewId
                    + "', 1, 0)");
        }
        try (SqlSession session = platformProjectionSessionFactory(dataSource).openSession()) {
            ReviewPlatformProjectionMapper mapper = session.getMapper(ReviewPlatformProjectionMapper.class);
            assertThat(mapper.findReviewPage("PENDING", false, null, 0L, 10))
                    .anySatisfy(row -> {
                        assertThat(row.reviewId()).isEqualTo(reviewId);
                        assertThat(row.stage()).isEqualTo("PENDING");
                        assertThat(row.eventId()).isNull();
                    });
            assertThat(mapper.countReviewPage("PENDING", false, null)).isGreaterThanOrEqualTo(1L);
        }
    }

    @Test
    void persistsAndReplaysRuntimeTraceThroughTheActualMybatisMapperOnMysql56() throws SQLException {
        MysqlDataSource dataSource = dataSource(MYSQL);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        String reviewId = java.util.UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO review_request "
                    + "(review_id, request_id, submitter_id, stage, input_idempotency_key, current_attempt_no, version) VALUES ('"
                    + reviewId + "', 'request-" + reviewId + "', 'reviewer', 'COMPLETED', 'idempotency-" + reviewId
                    + "', 1, 0)");
        }
        String runtimeId = "review-" + reviewId + "-attempt-1";
        SqlSessionFactory sessionFactory = runtimeTraceSessionFactory(dataSource);
        try (SqlSession session = sessionFactory.openSession(true)) {
            RuntimeTracePersistenceMapper mapper = session.getMapper(RuntimeTracePersistenceMapper.class);
            mapper.append(new RuntimeTracePersistenceMapper.RuntimeTraceRow(
                    runtimeId, 1, "RUN_STARTED:run-1", "RUN_STARTED", "{\"type\":\"RUN_STARTED\",\"runId\":\"run-1\"}",
                    reviewId, 1));
            mapper.append(new RuntimeTracePersistenceMapper.RuntimeTraceRow(
                    runtimeId, 2, "TEXT_MESSAGE_START:m-1", "TEXT_MESSAGE_START",
                    "{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m-1\"}", reviewId, 1));

            assertThat(mapper.maxSequence(runtimeId)).isEqualTo(2L);
            assertThat(mapper.findAfter(runtimeId, 0, 10)).hasSize(2)
                    .extracting(RuntimeTracePersistenceMapper.RuntimeTraceRow::sequence)
                    .containsExactly(1L, 2L);
            assertThat(mapper.findAfter(runtimeId, 1, 10)).singleElement()
                    .extracting(RuntimeTracePersistenceMapper.RuntimeTraceRow::sequence).isEqualTo(2L);

            // Re-inserting the same (runtime_id, event_sequence) is idempotent via ON DUPLICATE KEY.
            mapper.append(new RuntimeTracePersistenceMapper.RuntimeTraceRow(
                    runtimeId, 2, "TEXT_MESSAGE_START:m-1", "TEXT_MESSAGE_START",
                    "{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m-1\"}", reviewId, 1));
            assertThat(mapper.maxSequence(runtimeId)).isEqualTo(2L);
            assertThat(mapper.findAfter(runtimeId, 0, 10)).hasSize(2);

            mapper.trim(runtimeId, 1);
            assertThat(mapper.findAfter(runtimeId, 0, 10)).singleElement()
                    .extracting(RuntimeTracePersistenceMapper.RuntimeTraceRow::sequence).isEqualTo(2L);
        }
    }

    @Test
    void runtimeTraceEventSchemaIsMysql56CompositeKeySafe() throws SQLException {
        MysqlDataSource dataSource = dataSource(MYSQL);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(readColumnType(connection.getMetaData(), connection.getCatalog(), "runtime_trace_event", "payload_json"))
                    .isEqualTo("LONGTEXT");
            assertThat(readColumnType(connection.getMetaData(), connection.getCatalog(), "runtime_trace_event", "runtime_id"))
                    .isEqualTo("VARCHAR");
            // The ascii runtime_id keeps the (runtime_id, event_sequence) composite key inside the
            // 767-byte InnoDB limit even on MySQL 5.6.
            assertThat(readPrimaryKeyColumns(connection.getMetaData(), connection.getCatalog(), "runtime_trace_event"))
                    .containsExactly("RUNTIME_ID", "EVENT_SEQUENCE");
        }
    }

    private Set<String> readPrimaryKeyColumns(DatabaseMetaData metadata, String catalog, String tableName)
            throws SQLException {
        // JDBC getPrimaryKeys() row order is driver/server dependent (observed unordered on
        // MySQL 5.6 + Connector/J), so sort explicitly by KEY_SEQ before collecting names.
        java.util.Map<Integer, String> columnsByKeySeq = new java.util.TreeMap<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(catalog, null, tableName)) {
            while (resultSet.next()) {
                columnsByKeySeq.put(resultSet.getInt("KEY_SEQ"),
                        resultSet.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
            }
        }
        return new java.util.LinkedHashSet<>(columnsByKeySeq.values());
    }

    private Set<String> readIndexColumns(DatabaseMetaData metadata, String catalog, String tableName, String indexName)
            throws SQLException {
        // Connector/J getIndexInfo() row order is not guaranteed to follow index column order,
        // so sort explicitly by ORDINAL_POSITION before collecting names.
        java.util.Map<Integer, String> columnsByPosition = new java.util.TreeMap<>();
        try (ResultSet resultSet = metadata.getIndexInfo(catalog, null, tableName, false, false)) {
            while (resultSet.next()) {
                if (indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
                    columnsByPosition.put(resultSet.getInt("ORDINAL_POSITION"),
                            resultSet.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                }
            }
        }
        return new java.util.LinkedHashSet<>(columnsByPosition.values());
    }

    private MysqlDataSource dataSource(MySQLContainer<?> mysql) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(mysql.getJdbcUrl());
        dataSource.setUser(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        return dataSource;
    }

    private SqlSessionFactory reportMapperSessionFactory(MysqlDataSource dataSource) {
        Environment environment = new Environment("report-mapper", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ReviewReportMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private SqlSessionFactory platformProjectionSessionFactory(MysqlDataSource dataSource) {
        Environment environment = new Environment("platform-projection", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ReviewPlatformProjectionMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private SqlSessionFactory runtimeTraceSessionFactory(MysqlDataSource dataSource) {
        Environment environment = new Environment("runtime-trace", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(RuntimeTracePersistenceMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
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
                if ("review_event".equals(parts[0])) {
                    // The real v7 review_event envelope carried these columns before V8 widened payload_json;
                    // later index migrations (V10/V12/V13) reference them, so the stub must mirror that shape.
                    statement.execute("CREATE TABLE review_event (id BIGINT NOT NULL PRIMARY KEY, "
                            + "review_id CHAR(36) NULL, event_type VARCHAR(64) NULL, attempt_no INT NULL, "
                            + "event_sequence BIGINT NULL, occurred_at DATETIME(3) NULL, payload_json JSON NULL)");
                    continue;
                }
                statement.execute("CREATE TABLE " + parts[0] + " (id BIGINT NOT NULL PRIMARY KEY, " + parts[1] + " JSON NULL)");
            }
            // V11 links requirement to the pre-existing review_request table, so the legacy stub needs it too.
            statement.execute("CREATE TABLE review_request (review_id CHAR(36) NOT NULL PRIMARY KEY, "
                    + "request_id VARCHAR(128) NULL, submitter_id VARCHAR(128) NULL, stage VARCHAR(32) NULL, "
                    + "input_idempotency_key VARCHAR(128) NULL, current_attempt_no INT NULL, version BIGINT NULL) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
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
