package ai.cc.chongming.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.config.RepositoryAccessProperties;
import ai.cc.chongming.review.infrastructure.repository.RepositoryBoundaryGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests logical repository authorization and filesystem boundary rejection.
 *
 * @author wangli
 */
class RepositoryBoundaryGuardTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesOnlyAnAdministratorConfiguredRepositoryId() throws Exception {
        Path repository = createGitRepository("allowed-repository");
        RepositoryBoundaryGuard guard = guard("sample-repository", repository);

        var authorized = guard.requireAuthorized("sample-repository");

        assertThat(authorized.repositoryId()).isEqualTo("sample-repository");
        assertThat(authorized.root()).isEqualTo(repository.toRealPath());
        assertThatThrownBy(() -> guard.requireAuthorized(repository.toString()))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_NOT_CONFIGURED);
    }

    @Test
    void rejectsConfiguredRootsThatAreNotStandaloneGitRepositories() throws Exception {
        Path notRepository = temporaryDirectory.resolve("not-a-repository");
        Files.createDirectories(notRepository);
        RepositoryBoundaryGuard guard = guard("not-a-repository", notRepository);

        assertThatThrownBy(() -> guard.requireAuthorized("not-a-repository"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_NOT_GIT);
    }

    @Test
    void rejectsConfiguredRootsThatTraverseThroughASymbolicLink() throws Exception {
        Path repository = createGitRepository("actual-repository");
        Path linkedRepository = temporaryDirectory.resolve("linked-repository");
        try {
            Files.createSymbolicLink(linkedRepository, repository);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links are unavailable in this test environment");
        }
        RepositoryBoundaryGuard guard = guard("linked-repository", linkedRepository);

        assertThatThrownBy(() -> guard.requireAuthorized("linked-repository"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
    }

    @Test
    void rejectsConfiguredRootsThatAreWindowsJunctions() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path repository = createGitRepository("junction-target");
        Path junction = temporaryDirectory.resolve("junction-repository");
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), repository.toString())
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        Assumptions.assumeTrue(exitCode == 0, "Windows junctions are unavailable in this test environment");
        RepositoryBoundaryGuard guard = guard("junction-repository", junction);

        assertThatThrownBy(() -> guard.requireAuthorized("junction-repository"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
    }
    private RepositoryBoundaryGuard guard(String id, Path root) {
        return new RepositoryBoundaryGuard(new RepositoryAccessProperties(
                List.of(new RepositoryAccessProperties.RepositoryDefinition(id, root.toString(), null))));
    }

    private Path createGitRepository(String name) throws Exception {
        Path repository = temporaryDirectory.resolve(name);
        Files.createDirectories(repository.resolve("src"));
        Files.writeString(repository.resolve("src/App.java"), "class App {}\n", StandardCharsets.UTF_8);
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.invalid");
        git(repository, "config", "user.name", "Repository Test");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "initial snapshot fixture");
        return repository;
    }

    private void git(Path repository, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repository.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
