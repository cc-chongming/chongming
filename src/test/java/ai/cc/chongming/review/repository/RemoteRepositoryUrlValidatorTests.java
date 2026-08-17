package ai.cc.chongming.review.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryUrlValidator;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-028] Verifies the remote repository URL safety gate: scheme whitelist,
 * credential embedding, parent traversal, dangerous characters and private-range hosts.
 *
 * @author wangli
 */
class RemoteRepositoryUrlValidatorTests {

    private final RemoteRepositoryUrlValidator strictValidator = new RemoteRepositoryUrlValidator(false, false);
    private final RemoteRepositoryUrlValidator permissiveValidator = new RemoteRepositoryUrlValidator(true, true);

    @Test
    void acceptsAdministratorConfiguredHttpsUrls() {
        assertThatCode(() -> permissiveValidator.requireSafe("https://example.com/group/project.git"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsScpLikeSshRemotesOnPublicHosts() {
        assertThatCode(() -> strictValidator.requireSafe("git@example.com:group/project.git"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankUrls() {
        assertThatThrownBy(() -> strictValidator.requireSafe("  "))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
    }

    @Test
    void rejectsUnsupportedSchemes() {
        assertThatThrownBy(() -> strictValidator.requireSafe("http://example.com/project.git"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
        assertThatThrownBy(() -> strictValidator.requireSafe("ext::sh -c whoami"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
    }

    @Test
    void rejectsFileSchemeUnlessExplicitlyEnabledForTests() {
        assertThatThrownBy(() -> strictValidator.requireSafe("file:///D:/repositories/demo.git"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
        assertThatCode(() -> permissiveValidator.requireSafe("file:///D:/repositories/demo.git"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUrlsEmbeddingCredentials() {
        assertThatThrownBy(() -> strictValidator.requireSafe("https://user:token@example.com/project.git"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
    }

    @Test
    void rejectsParentDirectoryTraversal() {
        assertThatThrownBy(() -> strictValidator.requireSafe("https://example.com/../secrets.git"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
        assertThatThrownBy(() -> strictValidator.requireSafe("git@example.com:../secrets.git"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
    }

    @Test
    void rejectsControlCharactersAndShellMetacharacters() {
        assertThatThrownBy(() -> strictValidator.requireSafe("https://example.com/project.git`whoami`"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
    }

    @Test
    void rejectsHostsResolvingToPrivateRangesUnlessAllowed() {
        assertThatThrownBy(() -> strictValidator.requireSafe("https://localhost/project.git"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
        assertThatCode(() -> permissiveValidator.requireSafe("https://localhost/project.git"))
                .doesNotThrowAnyException();
        assertThatCode(() -> permissiveValidator.requireSafe("https://10.0.28.99/group/project.git"))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsUnresolvableHostsAsFetchFailures() {
        assertThatThrownBy(() -> strictValidator.requireSafe("https://chongming-nonexistent-host.invalid/project.git"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REMOTE_FETCH_FAILED);
    }
}
