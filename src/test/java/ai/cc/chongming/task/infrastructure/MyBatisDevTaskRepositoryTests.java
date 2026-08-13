package ai.cc.chongming.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.DevTaskMapper;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import ai.cc.chongming.task.domain.repository.DevTaskRepository.TaskFilter;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Guards the MySQL-backed development-task repository against a real Flyway-migrated schema,
 * including the optimistic-lock write path, the requirement-scoped unique key, the cascade
 * delete of tasks when their requirement is removed, the joined requirement title and
 * assignee display name on reads.
 *
 * @author wangli
 */
@Testcontainers(disabledWithoutDocker = true)
class MyBatisDevTaskRepositoryTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("chongming")
            .withUsername("review")
            .withPassword("review");

    static MyBatisDevTaskRepository repository;
    static MysqlDataSource dataSource;

    @BeforeAll
    static void migrateAndWireRepository() {
        dataSource = new MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        Environment environment = new Environment("dev-task", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(DevTaskMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        // SqlSessionTemplate mirrors the production MyBatis-Spring wiring, so a concurrent
        // duplicate insert surfaces as Spring's DuplicateKeyException exactly like at runtime.
        repository = new MyBatisDevTaskRepository(
                new SqlSessionTemplate(sessionFactory).getMapper(DevTaskMapper.class));
    }

    @Test
    void persistsDraftTaskAndRestoresItWithTheJoinedRequirementTitle() throws Exception {
        RequirementId requirementId = insertRequirement("派发联调需求");
        DevTask task = DevTask.draft(new DevTaskId(UUID.randomUUID()), requirementId, null, "派发联调任务");

        repository.save(task);

        DevTask restored = repository.findById(task.taskId()).orElseThrow();
        assertThat(restored.status()).isEqualTo(DevTaskStatus.PENDING_ASSIGN);
        assertThat(restored.version()).isZero();
        assertThat(restored.requirementTitle()).isEqualTo("派发联调需求");
        assertThat(repository.findByRequirementId(requirementId)).hasValueSatisfying(
                byRequirement -> assertThat(byRequirement.taskId()).isEqualTo(task.taskId()));
    }

    @Test
    void optimisticLockRejectsStaleWrites() throws Exception {
        RequirementId requirementId = insertRequirement("乐观锁需求");
        DevTask task = DevTask.draft(new DevTaskId(UUID.randomUUID()), requirementId, null, "乐观锁任务");
        repository.save(task);
        DevTask firstDispatch = restoreAndAssign(task, "first-owner");
        repository.save(firstDispatch);

        DevTask staleDispatch = restoreAndAssign(task, "stale-owner");
        assertThatThrownBy(() -> repository.save(staleDispatch))
                .isInstanceOf(TaskDomainException.class)
                .satisfies(exception -> assertThat(((TaskDomainException) exception).errorCode())
                        .isEqualTo(TaskErrorCode.VERSION_CONFLICT));
    }

    @Test
    void requirementScopedUniqueKeyRejectsSecondTaskInsert() throws Exception {
        RequirementId requirementId = insertRequirement("唯一键需求");
        repository.save(DevTask.draft(new DevTaskId(UUID.randomUUID()), requirementId, null, "第一个任务"));

        assertThatThrownBy(() -> repository.save(
                DevTask.draft(new DevTaskId(UUID.randomUUID()), requirementId, null, "第二个任务")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void pageReadFiltersByStatusAssigneeAndKeywordWithJoinedTitles() throws Exception {
        RequirementId firstRequirement = insertRequirement("登录改造");
        RequirementId secondRequirement = insertRequirement("报表导出");
        DevTask first = DevTask.draft(new DevTaskId(UUID.randomUUID()), firstRequirement, null, "实现登录改造");
        DevTask second = DevTask.draft(new DevTaskId(UUID.randomUUID()), secondRequirement, null, "实现报表导出");
        repository.save(first);
        repository.save(second);

        TaskFilter all = new TaskFilter(null, null, null, null);
        long totalBefore = repository.findPage(all, 1, 100).total();

        assertThat(repository.findPage(new TaskFilter(DevTaskStatus.PENDING_ASSIGN, null, "登录", null), 1, 100).items())
                .anySatisfy(task -> {
                    assertThat(task.taskId()).isEqualTo(first.taskId());
                    assertThat(task.requirementTitle()).isEqualTo("登录改造");
                });
        assertThat(repository.findPage(new TaskFilter(DevTaskStatus.PENDING_ASSIGN, null, "报表", null), 1, 100).items())
                .anySatisfy(task -> assertThat(task.taskId()).isEqualTo(second.taskId()));
        assertThat(repository.findPage(new TaskFilter(DevTaskStatus.DEVELOPING, null, null, null), 1, 100).items())
                .noneSatisfy(task -> assertThat(task.taskId()).isIn(first.taskId(), second.taskId()));
        assertThat(repository.findPage(all, 1, 100).total()).isEqualTo(totalBefore);
        assertThat(repository.countByStatus()).containsKey(DevTaskStatus.PENDING_ASSIGN);
    }

    @Test
    void pageReadFiltersByRequirementIdOnServerSide() throws Exception {
        RequirementId firstRequirement = insertRequirement("过滤需求甲");
        RequirementId secondRequirement = insertRequirement("过滤需求乙");
        DevTask first = DevTask.draft(new DevTaskId(UUID.randomUUID()), firstRequirement, null, "过滤任务甲");
        DevTask second = DevTask.draft(new DevTaskId(UUID.randomUUID()), secondRequirement, null, "过滤任务乙");
        repository.save(first);
        repository.save(second);

        DevTaskRepository.TaskPage filtered =
                repository.findPage(new TaskFilter(null, null, null, firstRequirement), 1, 100);

        assertThat(filtered.total()).isEqualTo(1L);
        assertThat(filtered.items()).singleElement()
                .satisfies(task -> assertThat(task.taskId()).isEqualTo(first.taskId()));
    }

    @Test
    void deletingRequirementCascadesAwayItsDevTask() throws Exception {
        RequirementId requirementId = insertRequirement("级联删除需求");
        DevTask task = DevTask.draft(new DevTaskId(UUID.randomUUID()), requirementId, null, "级联删除任务");
        repository.save(task);
        assertThat(repository.findByRequirementId(requirementId)).isPresent();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM requirement WHERE requirement_id = '" + requirementId.value() + "'");
        }

        assertThat(repository.findByRequirementId(requirementId)).isEmpty();
        assertThat(repository.findById(task.taskId())).isEmpty();
    }

    @Test
    void readsExposeTheJoinedAssigneeDisplayName() throws Exception {
        RequirementId requirementId = insertRequirement("显示名需求");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users (username, password_hash, display_name, role) "
                    + "VALUES ('dev-display', 'PBKDF2$210000$c2FsdA==$aGFzaA==', '李开发', 'USER')");
        }
        DevTask task = DevTask.draft(new DevTaskId(UUID.randomUUID()), requirementId, null, "显示名任务");
        repository.save(task);
        DevTask dispatched = restoreAndAssign(task, "dev-display");
        repository.save(dispatched);

        assertThat(repository.findById(task.taskId()))
                .hasValueSatisfying(restored -> assertThat(restored.assigneeDisplayName()).isEqualTo("李开发"));
        assertThat(repository.findPage(new TaskFilter(null, "dev-display", null, null), 1, 100).items())
                .anySatisfy(row -> {
                    assertThat(row.taskId()).isEqualTo(task.taskId());
                    assertThat(row.assigneeDisplayName()).isEqualTo("李开发");
                });
    }

    @Test
    void rejectsInvalidPageArgumentsBeforeQuerying() {
        assertThatIllegalArgumentException().isThrownBy(() -> repository.findPage(null, 0, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> repository.findPage(null, 1, 101));
    }

    private DevTask restoreAndAssign(DevTask task, String assignee) {
        DevTask restored = DevTask.restore(
                task.taskId(),
                task.requirementId(),
                null,
                task.title(),
                DevTaskStatus.PENDING_ASSIGN,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                0L);
        restored.assign(assignee, "admin", new ai.cc.chongming.task.domain.protocol.DevTaskStateMachine());
        return restored;
    }

    private RequirementId insertRequirement(String title) throws Exception {
        RequirementId requirementId = new RequirementId(UUID.randomUUID());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO requirement "
                    + "(requirement_id, title, description_md, requirement_status, creator_id, version) VALUES ('"
                    + requirementId.value() + "', '" + title + "', '任务派发验证', 'APPROVED', 'admin', 0)");
        }
        return requirementId;
    }
}
