package ai.cc.chongming.review.application;

import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.RemoteRepositorySource;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.SnapshotReference;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.repository.GitSnapshotReader;
import ai.cc.chongming.review.infrastructure.repository.GitSnapshotReader.GitMetadata;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryMaterializer;
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
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Freezes a safe local Git repository into an immutable, review-scoped filesystem snapshot.
 *
 * @author wangli
 */
@Service
public class RepositorySnapshotService {

    private static final Logger log = LoggerFactory.getLogger(RepositorySnapshotService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", "target", "node_modules", ".idea",
            // Local agent/build runtime state must never become reviewable content:
            // it dominated snapshots when a repository reviewed its own worktree.
            ".agentscope", ".qoder", ".claude", ".codex", ".learnings");
    // Root-only artifacts: generic names that are noise at the repository root but may be legitimate deeper.
    private static final Set<String> EXCLUDED_ROOT_DIRECTORIES = Set.of("output", "logs");
    private static final int BINARY_PROBE_SIZE = 8192;

    private final RepositoryBoundaryGuard boundaryGuard;
    private final GitSnapshotReader gitSnapshotReader;
    private final Path workspaceRoot;
    private final RemoteRepositoryMaterializer remoteMaterializer;
    private final RemoteTokenCipher remoteTokenCipher;
    private final ConcurrentMap<String, Object> snapshotLocks = new ConcurrentHashMap<>();

    public RepositorySnapshotService(
            RepositoryBoundaryGuard boundaryGuard,
            GitSnapshotReader gitSnapshotReader,
            ReviewProperties reviewProperties) {
        this(boundaryGuard, gitSnapshotReader, reviewProperties, null, null);
    }

    /**
     * [AIREVIEW-PLAN-029] Full constructor enabling requirement-supplied online repository
     * sources; without the materializer and cipher only configured repositories can bind.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RepositorySnapshotService(
            RepositoryBoundaryGuard boundaryGuard,
            GitSnapshotReader gitSnapshotReader,
            ReviewProperties reviewProperties,
            RemoteRepositoryMaterializer remoteMaterializer,
            RemoteTokenCipher remoteTokenCipher) {
        this.boundaryGuard = Objects.requireNonNull(boundaryGuard, "boundaryGuard must not be null");
        this.gitSnapshotReader = Objects.requireNonNull(gitSnapshotReader, "gitSnapshotReader must not be null");
        this.workspaceRoot = Path.of(reviewProperties.workspaceRoot()).toAbsolutePath().normalize();
        this.remoteMaterializer = remoteMaterializer;
        this.remoteTokenCipher = remoteTokenCipher;
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
        return bindSnapshot(reviewId, 1, repositoryId, "0".repeat(64), cancellation);
    }

    /**
     * Binds one attempt to a content-addressed shared snapshot before any Harness reads code.
     */
    public RepositorySnapshot bindSnapshot(
            ReviewId reviewId,
            int attemptNo,
            String repositoryId,
            String requirementSnapshotHash,
            IntakeCancellation cancellation) {
        return bindSnapshot(reviewId, attemptNo, RepositorySource.configured(repositoryId),
                requirementSnapshotHash, cancellation);
    }

    /**
     * [AIREVIEW-PLAN-029] Binds one attempt to a content-addressed shared snapshot resolved from
     * either a configured repository identity or a requirement-supplied online repository source.
     */
    public RepositorySnapshot bindSnapshot(
            ReviewId reviewId,
            int attemptNo,
            RepositorySource source,
            String requirementSnapshotHash,
            IntakeCancellation cancellation) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        cancellation.checkCancelled();
        AuthorizedRepository repository = resolveRepository(source);
        GitMetadata gitMetadata = gitSnapshotReader.read(repository.root(), cancellation);
        String worktreeFingerprint = gitMetadata.dirty()
                ? fingerprintRepository(repository.root(), cancellation)
                : "clean";
        String snapshotKey = sha256(repository.repositoryId() + "\u0000" + gitMetadata.headCommit() + "\u0000" + worktreeFingerprint);
        Path finalSnapshotDirectory = sharedSnapshotDirectory(repository.repositoryId(), snapshotKey);
        verifyWorkspacePath(finalSnapshotDirectory);
        Object lock = snapshotLocks.computeIfAbsent(snapshotKey, ignored -> new Object());
        synchronized (lock) {
            recoverIncompleteSnapshot(finalSnapshotDirectory, snapshotKey);
            RepositorySnapshot existing = readSharedSnapshot(
                    reviewId, repository, snapshotKey, worktreeFingerprint, finalSnapshotDirectory).orElse(null);
            if (existing != null) {
                writeReference(reviewId, attemptNo, snapshotKey, repository.repositoryId(), requirementSnapshotHash);
                return existing;
            }

            Path stagingDirectory = null;
            try {
                Files.createDirectories(finalSnapshotDirectory.getParent());
                stagingDirectory = Files.createTempDirectory(finalSnapshotDirectory.getParent(), ".snapshot-staging-");
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
                    snapshotKey,
                    worktreeFingerprint,
                    manifestHash,
                    collector.includedFileCount(),
                    capturedAt);
            cancellation.checkCancelled();
            moveDirectory(stagingDirectory, finalSnapshotDirectory);
            stagingDirectory = null;
                RepositorySnapshot created = new RepositorySnapshot(
                    UUID.nameUUIDFromBytes(snapshotKey.getBytes(StandardCharsets.UTF_8)),
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
                writeReference(reviewId, attemptNo, snapshotKey, repository.repositoryId(), requirementSnapshotHash);
                return created;
            } catch (RepositoryAccessException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Failed to create repository snapshot", exception);
            } finally {
                deleteDirectoryQuietly(stagingDirectory);
            }
        }
    }

    /**
     * Loads a previously frozen snapshot for the same review after a runtime restart instead of reading the host repository again.
     *
     * @author wangli
     */
    public Optional<RepositorySnapshot> findExistingSnapshot(ReviewId reviewId, String repositoryId) {
        return findExistingSnapshot(reviewId, 1, repositoryId);
    }

    /** Loads the shared snapshot selected by the immutable review reference. */
    public Optional<RepositorySnapshot> findExistingSnapshot(ReviewId reviewId, int attemptNo, String repositoryId) {
        return findExistingSnapshot(reviewId, attemptNo, RepositorySource.configured(repositoryId));
    }

    /**
     * [AIREVIEW-PLAN-029] Loads the shared snapshot selected by the immutable review reference
     * for either repository binding kind.
     */
    public Optional<RepositorySnapshot> findExistingSnapshot(ReviewId reviewId, int attemptNo, RepositorySource source) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        AuthorizedRepository repository = resolveRepository(source);
        Optional<SnapshotReference> reference = readReference(reviewId, attemptNo);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        if (!repository.repositoryId().equals(reference.orElseThrow().repositoryId())) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE,
                    "Review snapshot reference does not belong to the configured repository");
        }
        Path snapshotDirectory = sharedSnapshotDirectory(repository.repositoryId(), reference.orElseThrow().snapshotKey());
        Path repositoryDirectory = snapshotDirectory.resolve("repository").normalize();
        Path manifestPath = snapshotDirectory.resolve("snapshot-manifest.json").normalize();
        verifyWorkspacePath(snapshotDirectory);
        verifyWorkspacePath(repositoryDirectory);
        if (!Files.isDirectory(repositoryDirectory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            SnapshotMetadata metadata = OBJECT_MAPPER.readValue(manifestPath.toFile(), SnapshotMetadata.class);
            if (!reference.orElseThrow().snapshotKey().equals(metadata.snapshotKey())
                    || !repository.repositoryId().equals(metadata.repositoryId())
                    || !hashFile(manifestPath.getParent().resolve("repository-files.ndjson")).equals(metadata.manifestHash())) {
                throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Existing repository snapshot integrity validation failed");
            }
            touchMetadata(manifestPath, metadata);
            writeReference(reviewId, attemptNo, metadata.snapshotKey(), repository.repositoryId(),
                    reference.orElseThrow().requirementSnapshotHash());
            return Optional.of(toRepositorySnapshot(reviewId, repository, metadata, repositoryDirectory));
        } catch (RepositoryAccessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Existing repository snapshot cannot be loaded", exception);
        }
    }

    /** Removes one review-owned reference without deleting any shared snapshot. */
    public void removeReference(ReviewId reviewId, int attemptNo) {
        Path reference = referencePath(reviewId, attemptNo);
        try {
            Files.deleteIfExists(reference);
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Review snapshot reference cannot be removed", exception);
        }
    }

    /**
     * Removes only expired shared snapshots with no remaining reference. Each candidate is checked before and after
     * acquiring its per-key lock so a concurrently starting review cannot lose its snapshot.
     */
    public int cleanupUnreferencedSnapshots(Duration retention) {
        Objects.requireNonNull(retention, "retention must not be null");
        Path root = workspaceRoot.resolve("repository-snapshots").normalize();
        verifyWorkspacePath(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> directories = Files.walk(root, 2)) {
            for (Path candidate : directories.filter(path -> path.getNameCount() == root.getNameCount() + 2)
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path metadataPath = candidate.resolve("snapshot-manifest.json");
                if (!Files.isRegularFile(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                SnapshotMetadata metadata = OBJECT_MAPPER.readValue(metadataPath.toFile(), SnapshotMetadata.class);
                if (Instant.parse(metadata.lastAccessedAt()).plus(retention).isAfter(Instant.now())
                        || hasReference(metadata.snapshotKey())) {
                    continue;
                }
                Object lock = snapshotLocks.computeIfAbsent(metadata.snapshotKey(), ignored -> new Object());
                synchronized (lock) {
                        if (!hasReference(metadata.snapshotKey())) {
                            log.info("SHARED_REPOSITORY_SNAPSHOT_EXPIRED snapshotKey={}", metadata.snapshotKey());
                            deleteDirectory(candidate);
                            removed++;
                        }
                }
            }
            return removed;
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Shared repository snapshot cleanup failed", exception);
        }
    }

    /**
     * [AIREVIEW-PLAN-029] Resolves one repository binding into a snapshot-ready root: configured
     * identities travel through the boundary guard, online sources through the ad-hoc mirror
     * engine with the decrypted access token.
     */
    private AuthorizedRepository resolveRepository(RepositorySource source) {
        if (source.configuredRepositoryId() != null) {
            return boundaryGuard.requireAuthorized(source.configuredRepositoryId());
        }
        if (remoteMaterializer == null || remoteTokenCipher == null) {
            throw new RepositoryAccessException(
                    Code.REMOTE_FETCH_FAILED, "Remote repository support is not available");
        }
        RemoteRepositorySource remoteSource = source.remoteSource();
        String plainToken = remoteTokenCipher.decrypt(remoteSource.encryptedToken());
        Path mirrorRoot = remoteMaterializer.ensureAdhocMirror(remoteSource.url(), remoteSource.ref(), plainToken);
        return new AuthorizedRepository(source.repositoryIdentity(), mirrorRoot);
    }

    /**
     * Deletes only an unpublished snapshot directory that cannot be loaded by any runtime.
     * A published snapshot always contains both its repository root and metadata manifest.
     */
    private void recoverIncompleteSnapshot(Path snapshotDirectory, String snapshotKey) {
        if (!Files.exists(snapshotDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path repositoryDirectory = snapshotDirectory.resolve("repository").normalize();
        Path manifestPath = snapshotDirectory.resolve("snapshot-manifest.json").normalize();
        boolean published = Files.isDirectory(repositoryDirectory, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS);
        if (published) {
            return;
        }
        log.warn("SHARED_REPOSITORY_SNAPSHOT_INCOMPLETE_RECOVERED snapshotKey={} snapshotDirectory={}",
                snapshotKey, snapshotDirectory);
        deleteDirectory(snapshotDirectory);
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
                if (EXCLUDED_DIRECTORIES.contains(name)
                        || (relative.getNameCount() == 1 && EXCLUDED_ROOT_DIRECTORIES.contains(name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
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
            String snapshotKey,
            String worktreeFingerprint,
            String manifestHash,
            long includedFileCount,
            Instant capturedAt) throws IOException {
        SnapshotMetadata metadata = new SnapshotMetadata(
                snapshotKey,
                repository.repositoryId(),
                gitMetadata.headCommit(),
                gitMetadata.branch(),
                gitMetadata.dirty(),
                worktreeFingerprint,
                manifestHash,
                includedFileCount,
                capturedAt.toString(),
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

    private Path sharedSnapshotDirectory(String repositoryId, String snapshotKey) {
        Path directory = workspaceRoot.resolve("repository-snapshots")
                .resolve(sha256(repositoryId))
                .resolve(snapshotKey)
                .normalize();
        verifyWorkspacePath(directory);
        return directory;
    }

    private Path referencePath(ReviewId reviewId, int attemptNo) {
        Path directory = workspaceRoot.resolve("reviews").resolve(reviewId.value().toString())
                .resolve("attempts").resolve(Integer.toString(attemptNo)).normalize();
        verifyWorkspacePath(directory);
        return directory.resolve("snapshot-reference.json").normalize();
    }

    private boolean hasReference(String snapshotKey) {
        Path reviewsRoot = workspaceRoot.resolve("reviews").normalize();
        if (!Files.isDirectory(reviewsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(reviewsRoot)) {
            return files.filter(path -> path.getFileName().toString().equals("snapshot-reference.json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .anyMatch(path -> referenceMatches(path, snapshotKey));
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Shared snapshot references cannot be checked", exception);
        }
    }

    private boolean referenceMatches(Path path, String snapshotKey) {
        try {
            return snapshotKey.equals(OBJECT_MAPPER.readValue(path.toFile(), SnapshotReference.class).snapshotKey());
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Review snapshot reference cannot be checked", exception);
        }
    }

    private Optional<SnapshotReference> readReference(ReviewId reviewId, int attemptNo) {
        Path path = referencePath(reviewId, attemptNo);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.readValue(path.toFile(), SnapshotReference.class));
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Review snapshot reference cannot be loaded", exception);
        }
    }

    private void writeReference(
            ReviewId reviewId, int attemptNo, String snapshotKey, String repositoryId, String requirementSnapshotHash) {
        Path path = referencePath(reviewId, attemptNo);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            byte[] payload = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(new SnapshotReference(
                    reviewId, attemptNo, snapshotKey, repositoryId, Instant.now(), requirementSnapshotHash));
            Files.write(temporary, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Review snapshot reference cannot be written", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A later atomic write can safely replace a stale temporary reference.
            }
        }
    }

    private Optional<RepositorySnapshot> readSharedSnapshot(
            ReviewId reviewId,
            AuthorizedRepository repository,
            String snapshotKey,
            String worktreeFingerprint,
            Path snapshotDirectory) {
        Path repositoryDirectory = snapshotDirectory.resolve("repository").normalize();
        Path manifestPath = snapshotDirectory.resolve("snapshot-manifest.json").normalize();
        Path fileManifest = snapshotDirectory.resolve("repository-files.ndjson").normalize();
        if (!Files.isDirectory(repositoryDirectory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(fileManifest, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            SnapshotMetadata metadata = OBJECT_MAPPER.readValue(manifestPath.toFile(), SnapshotMetadata.class);
            if (!snapshotKey.equals(metadata.snapshotKey())
                    || !repository.repositoryId().equals(metadata.repositoryId())
                    || !worktreeFingerprint.equals(metadata.worktreeFingerprint())
                    || !hashFile(fileManifest).equals(metadata.manifestHash())) {
                throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Shared repository snapshot integrity validation failed");
            }
            touchMetadata(manifestPath, metadata);
            return Optional.of(toRepositorySnapshot(reviewId, repository, metadata, repositoryDirectory));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof RepositoryAccessException repositoryAccessException) {
                throw repositoryAccessException;
            }
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Shared repository snapshot cannot be loaded", exception);
        }
    }

    private RepositorySnapshot toRepositorySnapshot(
            ReviewId reviewId, AuthorizedRepository repository, SnapshotMetadata metadata, Path repositoryDirectory) {
        return new RepositorySnapshot(
                UUID.nameUUIDFromBytes(metadata.snapshotKey().getBytes(StandardCharsets.UTF_8)), reviewId,
                metadata.repositoryId(), repository.root(), repositoryDirectory, metadata.headCommit(), metadata.branch(),
                metadata.dirty(), metadata.manifestHash(), metadata.includedFileCount(), Instant.parse(metadata.createdAt()));
    }

    private void touchMetadata(Path manifestPath, SnapshotMetadata metadata) {
        Path temporary = null;
        SnapshotMetadata updated = new SnapshotMetadata(
                metadata.snapshotKey(), metadata.repositoryId(), metadata.headCommit(), metadata.branch(), metadata.dirty(),
                metadata.worktreeFingerprint(), metadata.manifestHash(), metadata.includedFileCount(), metadata.createdAt(),
                Instant.now().toString());
        try {
            temporary = Files.createTempFile(manifestPath.getParent(), ".snapshot-manifest-", ".tmp");
            Files.write(temporary, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(updated),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Shared repository snapshot access time cannot be updated", exception);
        } finally {
            try {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException ignored) {
                // The next access can replace a stale temporary metadata file.
            }
        }
    }

    private String fingerprintRepository(Path root, IntakeCancellation cancellation) {
        try (Stream<Path> paths = Files.walk(root)) {
            MessageDigest digest = sha256();
            paths.filter(path -> !path.equals(root)).sorted().forEach(path -> {
                cancellation.checkCancelled();
                if (isLinkedPath(path)) {
                    throw linkedPathException();
                }
                try {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        return;
                    }
                    Path relative = root.relativize(path);
                    String relativePath = toRelativePath(relative);
                    if (isExcluded(relative) || isSensitive(relativePath) || isBinary(path, cancellation)) {
                        return;
                    }
                    digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(hashFile(path).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                } catch (IOException exception) {
                    throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Repository file cannot be fingerprinted", exception);
                }
            });
            return hex(digest.digest());
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Repository cannot be fingerprinted", exception);
        }
    }

    private boolean isExcluded(Path relative) {
        int segmentIndex = 0;
        for (Path segment : relative) {
            String name = segment.toString();
            if (EXCLUDED_DIRECTORIES.contains(name)) {
                return true;
            }
            if (segmentIndex == 0 && EXCLUDED_ROOT_DIRECTORIES.contains(name)) {
                return true;
            }
            segmentIndex += 1;
        }
        return false;
    }

    private String hashFile(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private String sha256(String value) {
        return hex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void deleteDirectory(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new RepositoryAccessException(
                            Code.SNAPSHOT_FAILED, "Failed to recover incomplete repository snapshot", exception);
                }
            });
        } catch (IOException exception) {
            throw new RepositoryAccessException(
                    Code.SNAPSHOT_FAILED, "Failed to recover incomplete repository snapshot", exception);
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
            String snapshotKey,
            String repositoryId,
            String headCommit,
            String branch,
            boolean dirty,
            String worktreeFingerprint,
            String manifestHash,
            long includedFileCount,
            String createdAt,
            String lastAccessedAt) {
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
            writer.write('\n');
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
