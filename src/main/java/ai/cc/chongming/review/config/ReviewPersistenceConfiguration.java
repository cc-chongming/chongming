package ai.cc.chongming.review.config;

import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.extensions.mysql.sandbox.JdbcSandboxExecutionGuard;
import io.agentscope.extensions.mysql.snapshot.JdbcSnapshotSpec;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.time.Duration;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Wires business migrations and AgentScope runtime storage only when explicitly enabled.
 *
 * @author wangli
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@MapperScan("ai.cc.chongming.review.infrastructure.persistence.mapper")
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class ReviewPersistenceConfiguration {

    /**
     * Creates the shared datasource for MyBatis, Flyway and AgentScope runtime state.
     *
     * @param properties configured database settings
     * @return shared datasource
     */
    @Bean(destroyMethod = "close")
    @Primary
    public HikariDataSource reviewDataSource(ReviewPersistenceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.jdbcUrl());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setMaximumPoolSize(properties.maximumPoolSize());
        dataSource.setPoolName("chongming-review-pool");
        return dataSource;
    }

    /**
     * Runs only the application's versioned business migrations.
     *
     * @param dataSource shared datasource
     * @return migrated Flyway instance
     */
    @Bean
    public Flyway reviewFlyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }

    /**
     * Composes a named AgentScope MySQL store instead of relying on library defaults.
     *
     * <p>When schema initialization is disabled, AgentScope state and workspace tables must
     * already exist. AgentScope 2.0.0 exposes no non-initializing JDBC snapshot constructor, so
     * snapshots are intentionally disabled in that production-safe mode.
     *
     * @param dataSource shared datasource
     * @param properties configured persistence settings
     * @return AgentScope distributed store
     */
    @Bean
    @DependsOn("reviewFlyway")
    public DistributedStore reviewDistributedStore(
            DataSource dataSource, ReviewPersistenceProperties properties) {
        ReviewPersistenceProperties.AgentScopeMysqlProperties agentScope = properties.agentscope();
        boolean initializeSchema = agentScope.initializeSchema();
        SandboxSnapshotSpec snapshotSpec = initializeSchema
                ? new JdbcSnapshotSpec(dataSource, agentScope.snapshotTableName())
                : new NoopSnapshotSpec();

        return DistributedStore.builder()
                .agentStateStore(new MysqlAgentStateStore(
                        dataSource,
                        agentScope.databaseName(),
                        agentScope.stateTableName(),
                        initializeSchema))
                .baseStore(JdbcStore.builder(dataSource)
                        .tableName(agentScope.workspaceTableName())
                        .initializeSchema(initializeSchema)
                        .build())
                .sandboxSnapshotSpec(snapshotSpec)
                .sandboxExecutionGuard(JdbcSandboxExecutionGuard.builder(dataSource)
                        .keyPrefix(agentScope.lockKeyPrefix())
                        .lockTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
    }
}
