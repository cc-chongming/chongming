package ai.cc.chongming.review.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementFilter;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementPage;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementVisibility;
import ai.cc.chongming.review.infrastructure.persistence.mapper.RequirementMapper;
import ai.cc.chongming.review.infrastructure.persistence.repository.MyBatisRequirementRepository;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [AIREVIEW-PLAN-027] Viewer-scoped requirement reads against a real Flyway-migrated MySQL 5.6
 * schema: the ownership predicate combines creator rows with dev-task assignments, an empty
 * assigned set collapses to the creator condition alone, and totals stay consistent across the
 * paged read and the dashboard counts.
 *
 * @author wangli
 */
@Testcontainers(disabledWithoutDocker = true)
class RequirementViewerVisibilityIntegrationTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:5.6")
            .withDatabaseName("chongming")
            .withUsername("review")
            .withPassword("review");

    static MyBatisRequirementRepository repository;
    static MysqlDataSource dataSource;

    static RequirementId ownRequirement;
    static RequirementId assignedRequirement;
    static RequirementId foreignRequirement;

    @BeforeAll
    static void migrateAndSeedVisibilityFixtures() throws Exception {
        dataSource = new MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        Environment environment = new Environment("requirement-visibility", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(RequirementMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        repository = new MyBatisRequirementRepository(
                new SqlSessionTemplate(sessionFactory).getMapper(RequirementMapper.class));

        ownRequirement = insertRequirement("可见性自建需求", "dev-zhang");
        assignedRequirement = insertRequirement("可见性指派需求", "pm-wang");
        foreignRequirement = insertRequirement("可见性无关需求", "pm-wang");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO dev_task (task_id, requirement_id, title, task_status, assignee_username, version) "
                    + "VALUES ('" + UUID.randomUUID() + "', '" + assignedRequirement.value()
                    + "', '可见性指派任务', 'DEVELOPING', 'dev-zhang', 0)");
        }
    }

    @Test
    void viewerPageCombinesCreatorRowsWithAssignedRequirementsAndReportsConsistentTotals() {
        RequirementVisibility scoped = new RequirementVisibility("dev-zhang", Set.of(assignedRequirement));

        RequirementPage page = repository.findPage(new RequirementFilter(null, null, null, scoped), 1, 20);

        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.items())
                .extracting(requirement -> requirement.id())
                .containsExactlyInAnyOrder(ownRequirement, assignedRequirement);
        assertThat(repository.countByStatus(scoped)).containsEntry(RequirementStatus.DRAFT, 2L);
    }

    @Test
    void emptyAssignedSetCollapsesToTheCreatorConditionAlone() {
        RequirementVisibility creatorOnly = new RequirementVisibility("dev-zhang", Set.of());

        RequirementPage page = repository.findPage(new RequirementFilter(null, null, null, creatorOnly), 1, 20);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).singleElement()
                .satisfies(requirement -> assertThat(requirement.id()).isEqualTo(ownRequirement));
        assertThat(repository.countByStatus(creatorOnly)).containsEntry(RequirementStatus.DRAFT, 1L);
    }

    @Test
    void visibilityComposesWithHistoricalFilters() {
        RequirementVisibility scoped = new RequirementVisibility("pm-wang", Set.of());

        RequirementPage keywordPage = repository.findPage(
                new RequirementFilter(RequirementStatus.DRAFT, null, "指派", scoped), 1, 20);

        assertThat(keywordPage.total()).isEqualTo(1L);
        assertThat(keywordPage.items()).singleElement()
                .satisfies(requirement -> assertThat(requirement.id()).isEqualTo(assignedRequirement));
    }

    @Test
    void unrestrictedReadsKeepTheHistoricalStatements() {
        RequirementPage platformWide = repository.findPage(new RequirementFilter(null, null, "可见性", null), 1, 20);

        assertThat(platformWide.total()).isEqualTo(3L);
        assertThat(repository.countByStatus()).containsEntry(RequirementStatus.DRAFT, 3L);
    }

    private static RequirementId insertRequirement(String title, String creatorId) throws Exception {
        RequirementId requirementId = new RequirementId(UUID.randomUUID());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO requirement "
                    + "(requirement_id, title, description_md, requirement_status, creator_id, version) VALUES ('"
                    + requirementId.value() + "', '" + title + "', '可见性契约验证', 'DRAFT', '" + creatorId + "', 0)");
        }
        return requirementId;
    }
}
