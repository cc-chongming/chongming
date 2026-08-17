package ai.cc.chongming.review.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [AIREVIEW-PLAN-027] V23 migration contract on a real MySQL 5.6 server: legacy requirement
 * creators that are not registered users are folded into {@code admin}, rows owned by real
 * accounts stay untouched, the original creators survive in the backup table, and the creator
 * visibility index is created.
 *
 * @author wangli
 */
@Testcontainers(disabledWithoutDocker = true)
class RequirementCreatorVisibilityMigrationIntegrationTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:5.6")
            .withDatabaseName("chongming")
            .withUsername("review")
            .withPassword("review");

    @Test
    void v23FoldsUnknownCreatorsIntoAdminAndKeepsRegisteredOwners() throws Exception {
        MysqlDataSource dataSource = dataSource();
        // Run everything up to V22 first so legacy requirement rows exist before V23 executes.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("22")
                .load()
                .migrate();

        String sentinelRequirementId = UUID.randomUUID().toString();
        String registeredRequirementId = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users (username, password_hash, display_name, role) "
                    + "VALUES ('bob', 'PBKDF2$210000$c2FsdA==$aGFzaA==', 'Bob', 'DEVELOPER')");
            statement.executeUpdate("INSERT INTO requirement "
                    + "(requirement_id, title, requirement_status, creator_id, version) VALUES ('"
                    + sentinelRequirementId + "', '遗留哨兵需求', 'DRAFT', 'anonymous-sentinel', 0)");
            statement.executeUpdate("INSERT INTO requirement "
                    + "(requirement_id, title, requirement_status, creator_id, version) VALUES ('"
                    + registeredRequirementId + "', '注册用户需求', 'DRAFT', 'bob', 0)");
        }

        // V23 now runs against the seeded legacy data.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet sentinelRows = statement.executeQuery(
                    "SELECT creator_id FROM requirement WHERE requirement_id = '" + sentinelRequirementId + "'")) {
                assertThat(sentinelRows.next()).isTrue();
                assertThat(sentinelRows.getString(1)).isEqualTo("admin");
            }
            try (ResultSet registeredRows = statement.executeQuery(
                    "SELECT creator_id FROM requirement WHERE requirement_id = '" + registeredRequirementId + "'")) {
                assertThat(registeredRows.next()).isTrue();
                assertThat(registeredRows.getString(1)).isEqualTo("bob");
            }
            // The backup table preserves the pre-migration creators for both rows.
            Set<String> backupCreators = new HashSet<>();
            try (ResultSet backupRows = statement.executeQuery(
                    "SELECT creator_id FROM requirement_creator_backup_plan027 WHERE requirement_id IN ('"
                            + sentinelRequirementId + "', '" + registeredRequirementId + "')")) {
                while (backupRows.next()) {
                    backupCreators.add(backupRows.getString(1));
                }
            }
            assertThat(backupCreators).containsExactlyInAnyOrder("anonymous-sentinel", "bob");
            // The creator visibility index exists after the migration.
            boolean indexFound = false;
            try (ResultSet indexRows = statement.executeQuery("SHOW INDEX FROM requirement WHERE Key_name = 'idx_requirement_creator'")) {
                indexFound = indexRows.next();
            }
            assertThat(indexFound).isTrue();
            // The seeded administrator keeps its own requirements untouched.
            try (ResultSet adminRows = statement.executeQuery(
                    "SELECT COUNT(*) FROM users WHERE username = 'admin' AND role = 'ADMIN'")) {
                assertThat(adminRows.next()).isTrue();
                assertThat(adminRows.getLong(1)).isEqualTo(1L);
            }
        }
    }

    private MysqlDataSource dataSource() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }
}
