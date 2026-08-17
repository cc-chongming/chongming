package ai.cc.chongming.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote.Auth;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.RepositoryType;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryMaterializer;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryUrlValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * [AIREVIEW-PLAN-028] Exercises the remote mirror engine against a local bare repository that
 * stands in for the remote Git server (file:// URLs are whitelisted for tests only).
 *
 * @author wangli
 */
class RemoteRepositoryMaterializerTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void clonesAConfiguredRemoteIntoAServerManagedMirror() throws Exception {
        Path bareRemote = createBareRemote("origin-remote", "first version");
        RemoteRepositoryMaterializer materializer = newMaterializer();

        Path mirror = materializer.ensureMirror(remoteDefinition("demo-remote", bareRemote));

        assertThat(mirror).startsWith(temporaryDirectory.resolve("workspace").resolve("repository-mirrors"));
        assertThat(Files.isDirectory(mirror.resolve(".git"))).isTrue();
        assertThat(Files.readString(mirror.resolve("README.md"), StandardCharsets.UTF_8))
                .isEqualToNormalizingNewlines("first version\n");
        try (Stream<Path> entries = Files.list(mirrorDirectoryRoot(materializer))) {
            assertThat(entries.count()).isEqualTo(1);
        }
    }

    @Test
    void reusesTheMirrorAndFastForwardsToTheLatestRemoteCommit() throws Exception {
        Path bareRemote = createBareRemote("origin-remote", "first version");
        RemoteRepositoryMaterializer materializer = newMaterializer();
        RepositoryDefinition definition = remoteDefinition("demo-remote", bareRemote);

        materializer.ensureMirror(definition);
        pushCommit(bareRemote, "second version");
        Path refreshed = materializer.ensureMirror(definition);

        assertThat(Files.readString(refreshed.resolve("README.md"), StandardCharsets.UTF_8))
                .isEqualToNormalizingNewlines("second version\n");
        try (Stream<Path> entries = Files.list(mirrorDirectoryRoot(materializer))) {
            assertThat(entries.count()).as("the mirror must be reused, not duplicated").isEqualTo(1);
        }
    }

    @Test
    void resetsLocalMirrorModificationsBeforeTheNextCapture() throws Exception {
        Path bareRemote = createBareRemote("origin-remote", "first version");
        RemoteRepositoryMaterializer materializer = newMaterializer();
        RepositoryDefinition definition = remoteDefinition("demo-remote", bareRemote);

        Path mirror = materializer.ensureMirror(definition);
        Files.writeString(mirror.resolve("README.md"), "tampered\n", StandardCharsets.UTF_8);
        Files.writeString(mirror.resolve("untracked.txt"), "untracked\n", StandardCharsets.UTF_8);

        Path refreshed = materializer.ensureMirror(definition);

        assertThat(Files.readString(refreshed.resolve("README.md"), StandardCharsets.UTF_8))
                .isEqualToNormalizingNewlines("first version\n");
        assertThat(Files.exists(refreshed.resolve("untracked.txt"))).isFalse();
    }

    @Test
    void reportsUnavailableRemotesWithAStableFetchFailure() {
        RemoteRepositoryMaterializer materializer = newMaterializer();
        Path missing = temporaryDirectory.resolve("missing-remote");

        RepositoryDefinition definition = new RepositoryDefinition(
                "demo-remote", null, "演示远程仓库", RepositoryType.REMOTE,
                new Remote(missing.toUri().toString(), "main", new Auth(null, null, null), Duration.ofSeconds(30)));
        assertThatThrownBy(() -> materializer.ensureMirror(definition))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REMOTE_FETCH_FAILED);
    }

    @Test
    void rejectsUnsafeRemoteUrlsBeforeAnyCloneAttempt() {
        RemoteRepositoryMaterializer materializer = new RemoteRepositoryMaterializer(
                new ReviewProperties(temporaryDirectory.resolve("workspace").toString(), 1, 1),
                new RemoteRepositoryUrlValidator(false, false));
        RepositoryDefinition definition = new RepositoryDefinition(
                "demo-remote", null, "演示远程仓库", RepositoryType.REMOTE,
                new Remote("file:///D:/repositories/demo.git", "main", new Auth(null, null, null), null));

        assertThatThrownBy(() -> materializer.ensureMirror(definition))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
    }

    private RemoteRepositoryMaterializer newMaterializer() {
        return new RemoteRepositoryMaterializer(
                new ReviewProperties(temporaryDirectory.resolve("workspace").toString(), 1, 1),
                new RemoteRepositoryUrlValidator(true, true));
    }

    private Path mirrorDirectoryRoot(RemoteRepositoryMaterializer materializer) {
        return temporaryDirectory.resolve("workspace").resolve("repository-mirrors");
    }

    private RepositoryDefinition remoteDefinition(String repositoryId, Path bareRemote) {
        return new RepositoryDefinition(
                repositoryId, null, "演示远程仓库", RepositoryType.REMOTE,
                new Remote(bareRemote.toUri().toString(), "main", new Auth(null, null, null), Duration.ofSeconds(60)));
    }

    /** Creates a bare repository with one committed README on branch {@code main}. */
    private Path createBareRemote(String name, String content) throws Exception {
        Path bare = temporaryDirectory.resolve(name + ".git");
        Files.createDirectories(bare);
        git(bare, "init", "--bare");
        git(bare, "symbolic-ref", "HEAD", "refs/heads/main");

        Path seed = temporaryDirectory.resolve(name + "-seed");
        Files.createDirectories(seed);
        Files.writeString(seed.resolve("README.md"), content + "\n", StandardCharsets.UTF_8);
        git(seed, "init");
        git(seed, "config", "user.email", "test@example.invalid");
        git(seed, "config", "user.name", "Repository Test");
        git(seed, "add", ".");
        git(seed, "commit", "-m", "seed commit");
        git(seed, "push", bare.toString(), "HEAD:refs/heads/main");
        return bare;
    }

    /** Pushes one new commit updating the README through a disposable seed worktree. */
    private void pushCommit(Path bareRemote, String content) throws Exception {
        Path seed = temporaryDirectory.resolve("update-seed-" + System.nanoTime());
        git(temporaryDirectory, "clone", bareRemote.toString(), seed.toString());
        Files.writeString(seed.resolve("README.md"), content + "\n", StandardCharsets.UTF_8);
        git(seed, "config", "user.email", "test@example.invalid");
        git(seed, "config", "user.name", "Repository Test");
        git(seed, "add", ".");
        git(seed, "commit", "-m", "update commit");
        git(seed, "push", "origin", "HEAD:refs/heads/main");
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
