package ai.cc.chongming.review.application;

import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.repository.GitSnapshotReader;
import ai.cc.chongming.review.infrastructure.repository.GitSnapshotReader.GitMetadata;
import ai.cc.chongming.review.infrastructure.repository.RepositoryBoundaryGuard;
import ai.cc.chongming.review.infrastructure.repository.RepositoryBoundaryGuard.AuthorizedRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Freezes a safe local Git repository into an immutable, review-scoped filesystem snapshot.
 *
 * @author wangli
 */
@Service
public class RepositorySnapshotService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", "target", "node_modules", ".idea");
    private static final int BINARY_PROBE_SIZE = 8192;

    private final RepositoryBoundaryGuard boundaryGuard;
    private final GitSnapshotReader gitSnapshotReader;
    private final Path workspaceRoot;

    public RepositorySnapshotService(
            RepositoryBoundaryGuard boundaryGuard,
            GitSnapshotReader gitSnapshotReader,
            ReviewProperties reviewProperties) {
        this.boundaryGuard = Objects.requireNonNull(boundaryGuard, "boundaryGuard must not be null");
        this.gitSnapshotReader = Objects.requireNonNull(gitSnapshotReader, "gitSnapshotReader must not be null");
        this.workspaceRoot = Path.of(reviewProperties.workspaceRoot()).toAbsolutePath().normalize();
    }

    /**
     * Copies one authorized repository into the fixed review snapshot location without following links.
     *
     * @param reviewId review that owns the frozen repository state
     * @param repositoryId opaque administrator-configured repository identity
     * @param cancellation cancellation signal
     * @return immutable metadata and final snapshot locations
     */
    public RepositorySnapshot snapshot(ReviewId reviewId, String repositoryId, IntakeCancellation cancellation) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        cancellation.checkCancelled();
        AuthorizedRepository repository = boundaryGuard.requireAuthorized(repositoryId);
        GitMetadata gitMetadata = gitSnapshotReader.read(repository.root(), cancellation);

        Path reviewWorkspace = workspaceRoot.resolve("reviews").resolve(reviewId.value().toString()).normalize();
        Path finalSnapshotDirectory = reviewWorkspace.resolve("snapshot").normalize();
        verifyWorkspacePath(reviewWorkspace);
        verifyWorkspacePath(finalSnapshotDirectory);
        if (Files.exists(finalSnapshotDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new RepositoryAccessException(Code.SNAPSHOT_ALREADY_EXISTS, "Repository snapshot already exists for this review");
        }

        Path stagingDirectory = null;
        try {
            Files.createDirectories(reviewWorkspace);
            stagingDirectory = Files.createTempDirectory(reviewWorkspace, ".snapshot-staging-");
            Path repositoryStagingDirectory = stagingDirectory.resolve("repository");
            Files.createDirectories(repositoryStagingDirectory);

            ManifestCollector collector = new ManifestCollector(stagingDirectory.resolve("repository-files.ndjson"));
            try (collector) {
                copyRepository(repository.root(), repositoryStagingDirectory, collector, cancellation);
            }
            cancellation.checkCancelled();
            String manifestHash = collector.manifestHash();
            Instant capturedAt = Instant.now();
            writeSnapshotMetadata(
                    stagingDirectory.resolve("snapshot-manifest.json"),
                    repository,
                    gitMetadata,
                    manifestHash,
                    collector.includedFileCount(),
                    capturedAt);
            cancellation.checkCancelled();
            moveDirectory(stagingDirectory, finalSnapshotDirectory);
            stagingDirectory = null;
            return new RepositorySnapshot(
                    UUID.randomUUID(),
                    reviewId,
                    repository.repositoryId(),
                    repository.root(),
                    finalSnapshotDirectory.resolve("repository"),
                    gitMetadata.headCommit(),
                    gitMetadata.branch(),
                    gitMetadata.dirty(),
                    manifestHash,
                    collector.includedFileCount(),
                    capturedAt);
        } catch (RepositoryAccessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Failed to create repository snapshot", exception);
        } finally {
            deleteDirectoryQuietly(stagingDirectory);
        }
    }

    private void copyRepository(
            Path sourceRoot,
            Path targetRoot,
            ManifestCollector collector,
            IntakeCancellation cancellation) throws IOException {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                cancellation.checkCancelled();
                if (!directory.equals(sourceRoot) && isLinkedPath(directory)) {
                    throw linkedPathException();
                }
                if (directory.equals(sourceRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = sourceRoot.relativize(directory);
                String name = directory.getFileName().toString();
                if (".git".equals(name) && relative.getNameCount() == 1) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (".git".equals(name)) {
                    throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Nested Git metadata is not allowed");
                }
                return EXCLUDED_DIRECTORIES.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                cancellation.checkCancelled();
                if (isLinkedPath(file)) {
                    throw linkedPathException();
                }
                Path relative = sourceRoot.relativize(file);
                String relativePath = toRelativePath(relative);
                if (".git".equals(file.getFileName().toString())) {
                    throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Nested Git metadata is not allowed");
                }
                if (!attributes.isRegularFile() || isSensitive(relativePath) || isBinary(file, cancellation)) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = targetRoot.resolve(relative).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Repository file escapes snapshot root");
                }
                Files.createDirectories(target.getParent());
                String fileHash = copyAndHash(file, target, cancellation);
                collector.record(new RepositoryFileManifest(
                        relativePath,
                        attributes.size(),
                        attributes.lastModifiedTime().toInstant().toString(),
                        fileHash,
                        languageOf(relativePath),
                        Files.isReadable(file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Repository file cannot be read", exception);
            }
        });
    }

    private boolean isLinkedPath(Path path) {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        try {
            Object attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            return attributes instanceof Integer value && (value & 0x400) != 0;
        } catch (IOException | UnsupportedOperationException ignored) {
            return false;
        }
    }

    private RepositoryAccessException linkedPathException() {
        return new RepositoryAccessException(Code.SYMLINK_NOT_ALLOWED, "Symbolic links and junctions are not allowed in snapshots");
    }

    private boolean isSensitive(String relativePath) {
        String name = Path.of(relativePath).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(".env")
                || name.startsWith(".env.")
                || name.equals("id_rsa")
                || name.endsWith(".pem")
                || name.endsWith(".key")
                || name.contains("credential")
                || name.contains("secret");
    }

    private boolean isBinary(Path file, IntakeCancellation cancellation) throws IOException {
        int inspected = 0;
        int disallowedControls = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            for (int value; inspected < BINARY_PROBE_SIZE && (value = input.read()) != -1; inspected++) {
                cancellation.checkCancelled();
                if (value == 0) {
                    return true;
                }
                if (value < 32 && value != '\n' && value != '\r' && value != '\t' && value != '\f') {
                    disallowedControls++;
                }
            }
        }
        return inspected > 0 && disallowedControls * 100 > inspected * 5;
    }

    private String copyAndHash(Path source, Path target, IntakeCancellation cancellation) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source));
                OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            for (int read; (read = input.read(buffer)) != -1;) {
                cancellation.checkCancelled();
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private void writeSnapshotMetadata(
            Path target,
            AuthorizedRepository repository,
            GitMetadata gitMetadata,
            String manifestHash,
            long includedFileCount,
            Instant capturedAt) throws IOException {
        SnapshotMetadata metadata = new SnapshotMetadata(
                repository.repositoryId(),
                gitMetadata.headCommit(),
                gitMetadata.branch(),
                gitMetadata.dirty(),
                manifestHash,
                includedFileCount,
                capturedAt.toString());
        byte[] payload = OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(metadata)
                .getBytes(StandardCharsets.UTF_8);
        Files.write(target, payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void verifyWorkspacePath(Path path) {
        if (!path.startsWith(workspaceRoot)) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Snapshot path escapes the workspace root");
        }
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A later workspace cleanup can reconcile a failed staging-directory cleanup.
                }
            });
        } catch (IOException ignored) {
            // A later workspace cleanup can reconcile a failed staging-directory cleanup.
        }
    }

    private String toRelativePath(Path path) {
        String value = path.normalize().toString().replace(path.getFileSystem().getSeparator(), "/");
        if (value.isBlank() || value.startsWith("../") || value.equals("..")) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Repository file path is unsafe");
        }
        return value;
    }

    private String languageOf(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".js") || lower.endsWith(".ts")) {
            return "javascript";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".md")) {
            return "markdown";
        }
        if (lower.endsWith(".xml")) {
            return "xml";
        }
        return "text";
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    /**
     * One streamed, agent-inaccessible entry in the repository file manifest.
     *
     * @author wangli
     */
    private record RepositoryFileManifest(
            String relativePath,
            long size,
            String lastModifiedAt,
            String fileHash,
            String language,
            boolean readable) {
    }

    /**
     * Summary metadata stored beside the frozen repository directory.
     *
     * @author wangli
     */
    private record SnapshotMetadata(
            String repositoryId,
            String headCommit,
            String branch,
            boolean dirty,
            String manifestHash,
            long includedFileCount,
            String capturedAt) {
    }

    /**
     * Streams file-manifest rows to disk while incrementally calculating their SHA-256 hash.
     *
     * @author wangli
     */
    private static final class ManifestCollector implements AutoCloseable {

        private final BufferedWriter writer;
        private final MessageDigest digest = createDigest();
        private long includedFileCount;

        private ManifestCollector(Path output) throws IOException {
            this.writer = Files.newBufferedWriter(
                    output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        private void record(RepositoryFileManifest entry) throws IOException {
            String json = OBJECT_MAPPER.writeValueAsString(entry);
            writer.write(json);
            writer.newLine();
            digest.update(json.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            includedFileCount++;
        }

        private long includedFileCount() {
            return includedFileCount;
        }

        private String manifestHash() {
            return hexDigest(digest.digest());
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }

        private static MessageDigest createDigest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private static String hexDigest(byte[] bytes) {
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        }
    }
}
