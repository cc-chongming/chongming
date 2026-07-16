package ai.cc.chongming.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests bounded, snapshot-rooted repository listing, search and source reads.
 *
 * @author wangli
 */
class RepositorySearchIndexTests {

    @TempDir
    Path temporaryDirectory;

    private final RepositorySearchIndex index = new RepositorySearchIndex();

    @Test
    void listsSearchesAndReadsOnlyFrozenSnapshotFiles() throws Exception {
        RepositorySnapshot snapshot = snapshot();

        var files = index.listFiles(snapshot, 10, IntakeCancellation.neverCancelled());
        var matches = index.searchText(snapshot, "TODO", false, 10, IntakeCancellation.neverCancelled());
        var lines = index.readLines(snapshot, "src/App.java", 2, 2, IntakeCancellation.neverCancelled());
        var metadata = index.getFileMetadata(snapshot, "src/App.java");

        assertThat(files).extracting(file -> file.relativePath())
                .contains("README.md", "src/App.java")
                .doesNotContain(".env", "secret.bin");
        assertThat(matches).singleElement()
                .extracting(match -> match.relativePath(), match -> match.lineNumber(), match -> match.line())
                .containsExactly("src/App.java", 2, "// TODO: validate request");
        assertThat(lines).extracting(line -> line.lineNumber()).containsExactly(2, 3);
        assertThat(lines).extracting(line -> line.line()).containsExactly("// TODO: validate request", "}");
        assertThat(metadata.fileHash()).hasSize(64);
        assertThat(metadata.language()).isEqualTo("java");
    }

    @Test
    void rejectsUnsafeOrSensitivePathsAndRequestsBeyondTheResponseBudget() throws Exception {
        RepositorySnapshot snapshot = snapshot();

        assertThatThrownBy(() -> index.readLines(snapshot, "../outside.java", 1, 1, IntakeCancellation.neverCancelled()))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
        assertThatThrownBy(() -> index.readLines(snapshot, ".env", 1, 1, IntakeCancellation.neverCancelled()))
                .isInstanceOf(RepositoryAccessException.class)
                .extracting(exception -> ((RepositoryAccessException) exception).code())
                .isEqualTo(RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE);
        assertThat(index.searchText(snapshot, "TOKEN", false, 10, IntakeCancellation.neverCancelled())).isEmpty();
        assertThatThrownBy(() -> index.searchText(snapshot, "class", false, 101, IntakeCancellation.neverCancelled()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed budget");
    }

    private RepositorySnapshot snapshot() throws Exception {
        Path root = temporaryDirectory.resolve("snapshot/repository");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("README.md"), "# Repository\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/App.java"), "class App {\n// TODO: validate request\n}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env"), "TOKEN=do-not-expose\n", StandardCharsets.UTF_8);
        Files.write(root.resolve("secret.bin"), new byte[] {1, 0, 2});
        return new RepositorySnapshot(
                UUID.randomUUID(),
                new ReviewId(UUID.randomUUID()),
                "sample-repository",
                root,
                root,
                "a".repeat(40),
                "main",
                false,
                "b".repeat(64),
                4,
                Instant.now());
    }
}