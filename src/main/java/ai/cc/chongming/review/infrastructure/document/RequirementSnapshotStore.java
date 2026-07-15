package ai.cc.chongming.review.infrastructure.document;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.RequirementDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Writes immutable requirement artifacts atomically beneath the configured review workspace root.
 *
 * @author wangli
 */
@Component
public class RequirementSnapshotStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path workspaceRoot;

    public RequirementSnapshotStore(ReviewProperties properties) {
        this.workspaceRoot = Path.of(properties.workspaceRoot()).toAbsolutePath().normalize();
    }

    /**
     * Stores the raw Markdown, normalized Markdown and a manifest as one immutable attempt directory.
     *
     * @param snapshot immutable snapshot metadata
     * @param markdown temporary validated artifacts
     * @param cancellation request cancellation signal
     * @return final controlled workspace locations
     */
    public StoredRequirementSnapshot store(
            RequirementSnapshot snapshot, ValidatedMarkdown markdown, IntakeCancellation cancellation) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(markdown, "markdown must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");

        Path attemptDirectory = workspaceRoot
                .resolve("reviews")
                .resolve(snapshot.reviewId().value().toString())
                .resolve("attempt-" + snapshot.attemptNo())
                .normalize();
        Path inputDirectory = attemptDirectory.resolve("input").normalize();
        verifyWorkspacePath(attemptDirectory);
        verifyWorkspacePath(inputDirectory);

        Path stagingDirectory = null;
        try {
            cancellation.checkCancelled();
            Files.createDirectories(attemptDirectory);
            if (Files.exists(inputDirectory)) {
                throw new IllegalStateException("Requirement snapshot already exists for this review attempt");
            }
            stagingDirectory = Files.createTempDirectory(attemptDirectory, ".input-staging-");
            Path rawStaging = stagingDirectory.resolve("requirement.md");
            Path normalizedStaging = stagingDirectory.resolve("requirement.normalized.md");
            Path manifestStaging = stagingDirectory.resolve("snapshot-manifest.json");

            copyWithCancellation(markdown.rawFile(), rawStaging, cancellation);
            copyWithCancellation(markdown.normalizedFile(), normalizedStaging, cancellation);
            writeManifest(manifestStaging, snapshot, cancellation);
            cancellation.checkCancelled();
            moveDirectory(stagingDirectory, inputDirectory);
            stagingDirectory = null;
            return new StoredRequirementSnapshot(
                    inputDirectory.resolve("requirement.md"),
                    inputDirectory.resolve("requirement.normalized.md"),
                    inputDirectory.resolve("snapshot-manifest.json"));
        } catch (FileAlreadyExistsException exception) {
            throw new IllegalStateException("Requirement snapshot already exists for this review attempt", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store immutable requirement snapshot", exception);
        } finally {
            deleteDirectoryQuietly(stagingDirectory);
        }
    }

    private void verifyWorkspacePath(Path path) {
        if (!path.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Requirement snapshot path escapes the workspace root");
        }
    }

    private void copyWithCancellation(Path source, Path target, IntakeCancellation cancellation) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source));
                OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancellation.checkCancelled();
                output.write(buffer, 0, read);
            }
            cancellation.checkCancelled();
        }
    }

    private void writeManifest(Path target, RequirementSnapshot snapshot, IntakeCancellation cancellation) throws IOException {
        cancellation.checkCancelled();
        SnapshotManifest manifest = SnapshotManifest.from(snapshot);
        byte[] payload = OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest)
                .getBytes(StandardCharsets.UTF_8);
        cancellation.checkCancelled();
        Files.write(target, payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The controlled workspace is reconciled by the review cleanup workflow.
                }
            });
        } catch (IOException ignored) {
            // The controlled workspace is reconciled by the review cleanup workflow.
        }
    }

    /**
     * JSON metadata bound to the exact raw and normalized files in an immutable attempt snapshot.
     *
     * @author wangli
     */
    private record SnapshotManifest(
            String snapshotId,
            String reviewId,
            int attemptNo,
            String submitter,
            String repositoryPath,
            String branch,
            String commit,
            String originalFilename,
            String sourceHash,
            String contentHash,
            String parserVersion,
            String createdAt,
            RequirementDocument document) {

        private static SnapshotManifest from(RequirementSnapshot snapshot) {
            return new SnapshotManifest(
                    snapshot.snapshotId().toString(),
                    snapshot.reviewId().value().toString(),
                    snapshot.attemptNo(),
                    snapshot.submitter(),
                    snapshot.repositoryPath(),
                    snapshot.branch(),
                    snapshot.commit(),
                    snapshot.originalFilename(),
                    snapshot.sourceHash(),
                    snapshot.contentHash(),
                    snapshot.parserVersion(),
                    snapshot.createdAt().toString(),
                    snapshot.document());
        }
    }
}