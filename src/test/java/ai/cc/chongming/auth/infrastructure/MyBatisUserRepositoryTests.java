package ai.cc.chongming.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import ai.cc.chongming.auth.domain.User;
import ai.cc.chongming.review.infrastructure.persistence.mapper.UserMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
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
 * Regression guard for the MySQL-backed user repository: the users table uses an
 * auto-increment key, but the mapper row is an immutable record, so the repository must
 * re-read the persisted row instead of relying on generated-key backfill.
 *
 * @author wangli
 */
@Testcontainers(disabledWithoutDocker = true)
class MyBatisUserRepositoryTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("chongming")
            .withUsername("review")
            .withPassword("review");

    static MyBatisUserRepository repository;

    @BeforeAll
    static void migrateAndWireRepository() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        Environment environment = new Environment("auth-user", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(UserMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        // SqlSessionTemplate mirrors the production MyBatis-Spring wiring, so a concurrent
        // duplicate insert surfaces as Spring's DuplicateKeyException exactly like at runtime.
        repository = new MyBatisUserRepository(
                new SqlSessionTemplate(sessionFactory).getMapper(UserMapper.class));
    }

    @Test
    void saveReturnsPersistedUserWithGeneratedIdentifier() {
        User saved = repository.save(
                User.newUser("regression-user", "PBKDF2$210000$c2FsdA==$aGFzaA==", "Regression", "USER"));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.username()).isEqualTo("regression-user");
        assertThat(saved.passwordHash()).isEqualTo("PBKDF2$210000$c2FsdA==$aGFzaA==");
        assertThat(saved.displayName()).isEqualTo("Regression");
        assertThat(saved.role()).isEqualTo("USER");
        assertThat(repository.findByUsername("regression-user")).contains(saved);
        assertThat(repository.findById(saved.id())).contains(saved);
    }

    @Test
    void secondSaveAssignsDistinctIdentifier() {
        User first = repository.save(
                User.newUser("sequence-first", "PBKDF2$210000$c2FsdA==$aGFzaA==", null, "USER"));
        User second = repository.save(
                User.newUser("sequence-second", "PBKDF2$210000$c2FsdA==$aGFzaA==", null, "USER"));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isNotNull();
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void duplicateUsernameSurfacesUsernameTakenAfterInsert() {
        repository.save(
                User.newUser("taken-user", "PBKDF2$210000$c2FsdA==$aGFzaA==", "First", "USER"));

        assertThatThrownBy(() -> repository.save(
                User.newUser("taken-user", "PBKDF2$210000$b3RoZXI=$b3RoZXI=", "Second", "USER")))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).errorCode())
                        .isEqualTo(AuthErrorCode.USERNAME_TAKEN));
        assertThat(repository.findByUsername("taken-user"))
                .hasValueSatisfying(existing -> assertThat(existing.displayName()).isEqualTo("First"));
    }

    @Test
    void findByUnknownUsernameIsEmpty() {
        assertThat(repository.findByUsername("ghost-user")).isEmpty();
    }
}
