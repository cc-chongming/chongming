package ai.cc.chongming.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.config.RepositoryAccessProperties;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote.Auth;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.RepositoryType;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryMaterializer;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryUrlValidator;
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
                List.of(new RepositoryAccessProperties.RepositoryDefinition(id, root.toString(), null, null, null)), null, null));
    }

    /**
     * [AIREVIEW-PLAN-028] Remote entries resolve through the mirror materializer to a
     * snapshot-ready worktree owned by the workspace, never by the caller.
     */
    @Test
    void resolvesRemoteRepositoriesThroughTheManagedMirror() throws Exception {
        Path bareRemote = temporaryDirectory.resolve("remote-origin.git");
        Files.createDirectories(bareRemote);
        git(bareRemote, "init", "--bare");
        git(bareRemote, "symbolic-ref", "HEAD", "refs/heads/main");
        Path seed = temporaryDirectory.resolve("remote-seed");
        Files.createDirectories(seed);
        Files.writeString(seed.resolve("README.md"), "remote fixture\n", StandardCharsets.UTF_8);
        git(seed, "init");
        git(seed, "config", "user.email", "test@example.invalid");
        git(seed, "config", "user.name", "Repository Test");
        git(seed, "add", ".");
        git(seed, "commit", "-m", "remote seed commit");
        git(seed, "push", bareRemote.toString(), "HEAD:refs/heads/main");

        RepositoryDefinition definition = new RepositoryDefinition(
                "demo-remote", null, "演示远程仓库", RepositoryType.REMOTE,
                new Remote(bareRemote.toUri().toString(), "main", new Auth(null, null, null), null));
        RemoteRepositoryMaterializer materializer = new RemoteRepositoryMaterializer(
                new ReviewProperties(temporaryDirectory.resolve("workspace").toString(), 1, 1),
                new RemoteRepositoryUrlValidator(true, true));
        RepositoryBoundaryGuard guard = new RepositoryBoundaryGuard(
                new RepositoryAccessProperties(List.of(definition), null, null), materializer);

        RepositoryBoundaryGuard.AuthorizedRepository authorized = guard.requireAuthorized("demo-remote");

        assertThat(authorized.repositoryId()).isEqualTo("demo-remote");
        assertThat(authorized.root()).startsWith(temporaryDirectory.resolve("workspace").resolve("repository-mirrors"));
        assertThat(Files.isDirectory(authorized.root().resolve(".git"))).isTrue();
        assertThat(Files.readString(authorized.root().resolve("README.md"), StandardCharsets.UTF_8))
                .isEqualToNormalizingNewlines("remote fixture\n");
    }

    /** [AIREVIEW-PLAN-028] Without the materializer a remote entry fails with a stable code. */
    @Test
    void rejectsRemoteRepositoriesWhenMaterializationIsUnavailable() {
        RepositoryDefinition definition = new RepositoryDefinition(
                "demo-remote", null, "演示远程仓库", RepositoryType.REMOTE,
                new Remote("https://example.com/demo.git", "main", new Auth(null, null, null), null));
        RepositoryBoundaryGuard guard = new RepositoryBoundaryGuard(
                new RepositoryAccessProperties(List.of(definition), null, null));

        assertThatThrownBy(() -> guard.requireAuthorized("demo-remote"))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REMOTE_FETCH_FAILED);
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
