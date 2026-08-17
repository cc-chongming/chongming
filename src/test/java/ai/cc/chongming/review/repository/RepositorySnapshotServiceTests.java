package ai.cc.chongming.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.application.RepositorySnapshotService;
import ai.cc.chongming.review.config.RepositoryAccessProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.repository.GitSnapshotReader;
import ai.cc.chongming.review.infrastructure.repository.RepositoryBoundaryGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests immutable, filtered repository snapshots without executing repository code.
 *
 * @author wangli
 */
class RepositorySnapshotServiceTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void freezesSafeTextFilesAndStreamsMetadataWithoutCopyingSecretsOrBinaries() throws Exception {
        Path repository = createGitRepository("snapshot-repository");
        Files.writeString(repository.resolve("README.md"), "# Snapshot\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve(".env"), "TOKEN=do-not-copy\n", StandardCharsets.UTF_8);
        Files.write(repository.resolve("logo.bin"), new byte[] {1, 2, 0, 3});
        Files.createDirectories(repository.resolve("target"));
        Files.writeString(repository.resolve("target/generated.txt"), "ignored", StandardCharsets.UTF_8);
        RepositorySnapshotService service = newService(repository);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());

        RepositorySnapshot snapshot = service.snapshot(reviewId, "sample-repository", IntakeCancellation.neverCancelled());

        assertThat(snapshot.snapshotRepositoryRoot()).isDirectory();
        assertThat(snapshot.includedFileCount()).isEqualTo(2);
        assertThat(snapshot.manifestHash()).hasSize(64);
        assertThat(snapshot.headCommit()).hasSize(40);
        assertThat(snapshot.snapshotRepositoryRoot().resolve("src/App.java")).hasContent("class App {}\n");
        assertThat(snapshot.snapshotRepositoryRoot().resolve("README.md")).hasContent("# Snapshot\n");
        assertThat(snapshot.snapshotRepositoryRoot().resolve(".env")).doesNotExist();
        assertThat(snapshot.snapshotRepositoryRoot().resolve("logo.bin")).doesNotExist();
        assertThat(snapshot.snapshotRepositoryRoot().resolve("target")).doesNotExist();
        assertThat(Files.readString(snapshot.snapshotRepositoryRoot().getParent().resolve("repository-files.ndjson")))
                .contains("\"relativePath\":\"src/App.java\"");
        assertThat(Files.readString(snapshot.snapshotRepositoryRoot().getParent().resolve("snapshot-manifest.json")))
                .contains(snapshot.manifestHash());

        Files.writeString(repository.resolve("src/App.java"), "class App { int changed; }\n", StandardCharsets.UTF_8);
        assertThat(snapshot.snapshotRepositoryRoot().resolve("src/App.java")).hasContent("class App {}\n");
    }

    @Test
    void reloadsTheSameFrozenSnapshotWithoutReadingTheHostRepositoryAgain() throws Exception {
        Path repository = createGitRepository("reusable-snapshot-repository");
        RepositorySnapshotService service = newService(repository);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        RepositorySnapshot captured = service.snapshot(reviewId, "sample-repository", IntakeCancellation.neverCancelled());

        Files.writeString(repository.resolve("src/App.java"), "class App { int changed; }\n", StandardCharsets.UTF_8);
        RepositorySnapshot reloaded = service.findExistingSnapshot(reviewId, "sample-repository").orElseThrow();

        assertThat(reloaded.repositoryId()).isEqualTo(captured.repositoryId());
        assertThat(reloaded.manifestHash()).isEqualTo(captured.manifestHash());
        assertThat(reloaded.snapshotRepositoryRoot().resolve("src/App.java")).hasContent("class App {}\n");
    }

    @Test
    void replacesAnIncompleteSnapshotDirectoryBeforeCapturingTheRepository() throws Exception {
        Path repository = createGitRepository("incomplete-snapshot-repository");
        RepositorySnapshotService service = newService(repository);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Path incompleteSnapshot = temporaryDirectory.resolve("workspace/reviews")
                .resolve(reviewId.value().toString())
                .resolve("snapshot");
        Files.createDirectories(incompleteSnapshot);

        RepositorySnapshot snapshot = service.snapshot(reviewId, "sample-repository", IntakeCancellation.neverCancelled());

        assertThat(snapshot.snapshotRepositoryRoot()).isDirectory();
        assertThat(snapshot.snapshotRepositoryRoot().getParent().resolve("snapshot-manifest.json")).isRegularFile();
        assertThat(snapshot.snapshotRepositoryRoot().resolve("src/App.java")).hasContent("class App {}\n");
    }

    @Test
    void doesNotPublishSnapshotWhenCancelledBeforeCopying() throws Exception {
        Path repository = createGitRepository("cancelled-repository");
        RepositorySnapshotService service = newService(repository);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());

        assertThatThrownBy(() -> service.snapshot(reviewId, "sample-repository", () -> true))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cancelled");
        assertThat(temporaryDirectory.resolve("workspace/reviews").resolve(reviewId.value().toString())).doesNotExist();
    }

    @Test
    void rejectsLinkedFilesInsteadOfFollowingThemIntoTheSnapshot() throws Exception {
        Path repository = createGitRepository("linked-file-repository");
        Path outsideFile = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outsideFile, "outside", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(repository.resolve("linked.txt"), outsideFile);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links are unavailable in this test environment");
        }
        RepositorySnapshotService service = newService(repository);

        assertThatThrownBy(() -> service.snapshot(
                        new ReviewId(UUID.randomUUID()), "sample-repository", IntakeCancellation.neverCancelled()))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.SYMLINK_NOT_ALLOWED);
    }

    private RepositorySnapshotService newService(Path repository) {
        RepositoryBoundaryGuard guard = new RepositoryBoundaryGuard(new RepositoryAccessProperties(
                List.of(new RepositoryAccessProperties.RepositoryDefinition(
                        "sample-repository", repository.toString(), null, null, null)), null, null));
        return new RepositorySnapshotService(
                guard,
                new GitSnapshotReader(),
                new ReviewProperties(temporaryDirectory.resolve("workspace").toString(), 8, 2));
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
