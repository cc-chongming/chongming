package ai.cc.chongming.review.infrastructure.repository;

import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote.Auth.AuthType;
import ai.cc.chongming.review.config.ReviewProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-028] Materializes administrator-configured remote Git repositories into
 * server-managed shallow mirrors under {@code repository-mirrors/}. The first access clones
 * {@code --depth 1 --single-branch}; later accesses reuse the mirror through a bounded fetch and
 * hard reset, and any corrupted mirror is replaced by a fresh clone. Credentials are injected
 * through process environment variables only, never through command-line arguments or logs.
 *
 * @author wangli
 */
@Component
public class RemoteRepositoryMaterializer {

    private static final Logger log = LoggerFactory.getLogger(RemoteRepositoryMaterializer.class);
    private static final int CAPTURED_OUTPUT_LIMIT = 4096;
    private static final Duration STALE_STAGING_RETENTION = Duration.ofHours(1);

    private final Path mirrorsRoot;
    private final RemoteRepositoryUrlValidator urlValidator;
    private final ConcurrentMap<String, Object> repositoryLocks = new ConcurrentHashMap<>();

    public RemoteRepositoryMaterializer(ReviewProperties reviewProperties, RemoteRepositoryUrlValidator urlValidator) {
        Objects.requireNonNull(reviewProperties, "reviewProperties must not be null");
        this.urlValidator = Objects.requireNonNull(urlValidator, "urlValidator must not be null");
        this.mirrorsRoot = Path.of(reviewProperties.workspaceRoot())
                .toAbsolutePath().normalize().resolve("repository-mirrors");
    }

    /**
     * Ensures one configured remote repository exists locally as a clean shallow worktree and
     * returns its root. Concurrent callers for the same repository are serialized.
     *
     * @param definition administrator-configured remote repository definition
     * @return canonical mirror worktree root ready for snapshot capture
     */
    public Path ensureMirror(RepositoryDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        Remote remote = definition.remote();
        if (remote == null) {
            throw new RepositoryAccessException(Code.REPOSITORY_NOT_CONFIGURED, "Repository is not configured as remote");
        }
        if (urlValidator != null) {
            urlValidator.requireSafe(remote.url());
        }
        Object lock = repositoryLocks.computeIfAbsent(definition.id(), ignored -> new Object());
        synchronized (lock) {
            Path mirror = mirrorDirectory(definition.id());
            if (isUsableMirror(mirror, remote)) {
                try {
                    updateMirror(mirror, remote);
                    return mirror.toRealPath();
                } catch (RepositoryAccessException exception) {
                    log.warn("REMOTE_REPOSITORY_MIRROR_UPDATE_FAILED repositoryId={} code={}",
                            definition.id(), exception.code());
                    deleteDirectoryQuietly(mirror);
                } catch (IOException exception) {
                    log.warn("REMOTE_REPOSITORY_MIRROR_UPDATE_FAILED repositoryId={}", definition.id(), exception);
                    deleteDirectoryQuietly(mirror);
                }
            } else if (Files.exists(mirror, LinkOption.NOFOLLOW_LINKS)) {
                // Only an unpublished/incomplete mirror is ever removed; the retry below re-clones.
                deleteDirectoryQuietly(mirror);
            }
            cloneMirror(definition.id(), mirror, remote);
            try {
                return mirror.toRealPath();
            } catch (IOException exception) {
                throw new RepositoryAccessException(
                        Code.REMOTE_FETCH_FAILED, "Remote repository mirror is unreadable", exception);
            }
        }
    }

    private boolean isUsableMirror(Path mirror, Remote remote) {
        if (!Files.isDirectory(mirror.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String configuredUrl = remote.url();
        String originUrl = runCapture(mirror, remote, Duration.ofSeconds(10), false, "remote", "get-url", "origin");
        return configuredUrl.equals(originUrl);
    }

    private void updateMirror(Path mirror, Remote remote) throws IOException {
        String effectiveRef = remote.ref() != null
                ? remote.ref()
                : runCapture(mirror, remote, Duration.ofSeconds(10), false, "symbolic-ref", "--short", "-q", "HEAD");
        if (effectiveRef == null || effectiveRef.isBlank()) {
            throw new RepositoryAccessException(Code.REMOTE_FETCH_FAILED, "Remote repository mirror has no branch");
        }
        runRequired(mirror, remote, remote.cloneTimeout(), true, "fetch", "--depth", "1", "--force", "--quiet", "origin");
        runRequired(mirror, remote, remote.cloneTimeout(), false,
                "checkout", "--force", "--quiet", "-B", effectiveRef, "origin/" + effectiveRef);
        runRequired(mirror, remote, Duration.ofMinutes(2), false, "clean", "-fdx", "--quiet");
    }

    private void cloneMirror(String repositoryId, Path mirror, Remote remote) {
        try {
            Files.createDirectories(mirrorsRoot);
            cleanStaleStaging();
            Path staging = Files.createTempDirectory(mirrorsRoot, ".mirror-staging-");
            try {
                List<String> arguments = new ArrayList<>(List.of(
                        "clone", "--depth", "1", "--single-branch", "--quiet"));
                if (remote.ref() != null) {
                    arguments.add("--branch");
                    arguments.add(remote.ref());
                }
                arguments.add("--");
                arguments.add(remote.url());
                arguments.add(staging.toString());
                runGit(null, remote, remote.cloneTimeout(), true, arguments);
                Files.createDirectories(mirror.getParent());
                moveDirectory(staging, mirror);
                log.info("REMOTE_REPOSITORY_MIRROR_CLONED repositoryId={}", repositoryId);
            } finally {
                deleteDirectoryQuietly(staging);
            }
        } catch (RepositoryAccessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new RepositoryAccessException(
                    Code.REMOTE_FETCH_FAILED, "Remote repository mirror cannot be created", exception);
        }
    }

    private String runCapture(
            Path workingDirectory, Remote remote, Duration timeout, boolean networkCommand, String... arguments) {
        try {
            return runGit(workingDirectory, remote, timeout, networkCommand, List.of(arguments));
        } catch (RepositoryAccessException exception) {
            return null;
        }
    }

    private void runRequired(
            Path workingDirectory, Remote remote, Duration timeout, boolean networkCommand, String... arguments) {
        runGit(workingDirectory, remote, timeout, networkCommand, List.of(arguments));
    }

    /**
     * Runs one narrowly scoped, non-interactive Git command with credential injection limited to
     * process environment variables. Output is drained concurrently and kept bounded so a large
     * transfer can never stall the wait loop.
     */
    private String runGit(
            Path workingDirectory, Remote remote, Duration timeout, boolean networkCommand, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        if (workingDirectory != null) {
            command.add("-C");
            command.add(workingDirectory.toString());
        }
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        if (networkCommand) {
            injectCredentials(environment, remote);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            Process process = builder.start();
            StringBuilder captured = new StringBuilder();
            Thread drainer = drainOutput(process.getInputStream(), captured);
            try {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (System.nanoTime() >= deadline) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                        throw new RepositoryAccessException(
                                Code.REMOTE_FETCH_FAILED, "Remote repository fetch timed out");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        process.destroyForcibly();
                        throw new RepositoryAccessException(
                                Code.REMOTE_FETCH_FAILED, "Remote repository fetch was interrupted");
                    }
                }
                drainer.join(TimeUnit.SECONDS.toMillis(5));
                int exitCode = process.exitValue();
                String output = captured.toString();
                if (exitCode != 0) {
                    log.warn("REMOTE_REPOSITORY_GIT_FAILED exitCode={} output={}", exitCode, truncate(output));
                    throw classifyFailure(output);
                }
                return output.trim();
            } finally {
                drainer.interrupt();
            }
        } catch (RepositoryAccessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RepositoryAccessException(
                    Code.REMOTE_FETCH_FAILED, "Remote repository fetch was interrupted", exception);
        } catch (IOException exception) {
            throw new RepositoryAccessException(
                    Code.REMOTE_FETCH_FAILED, "Git executable is unavailable", exception);
        }
    }

    private void injectCredentials(Map<String, String> environment, Remote remote) {
        AuthType authType = remote.auth().type();
        if (authType == AuthType.HTTPS_TOKEN) {
            String token = System.getenv(remote.auth().tokenEnv());
            if (token == null || token.isBlank()) {
                throw new RepositoryAccessException(
                        Code.REMOTE_AUTH_FAILED, "Remote repository credentials are not configured");
            }
            String basic = Base64.getEncoder().encodeToString(
                    ("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            environment.put("GIT_CONFIG_COUNT", "1");
            environment.put("GIT_CONFIG_KEY_0", "http.extraheader");
            environment.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
            return;
        }
        if (authType == AuthType.SSH_KEY) {
            String keyPathEnvironment = remote.auth().keyPathEnv();
            String keyPath = keyPathEnvironment == null ? null : System.getenv(keyPathEnvironment);
            if (keyPath == null || keyPath.isBlank() || keyPath.contains("\"")) {
                throw new RepositoryAccessException(
                        Code.REMOTE_AUTH_FAILED, "Remote repository SSH key is not configured");
            }
            Path keyFile = Path.of(keyPath);
            if (!Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new RepositoryAccessException(
                        Code.REMOTE_AUTH_FAILED, "Remote repository SSH key is not configured");
            }
            environment.put("GIT_SSH_COMMAND",
                    "ssh -i \"" + keyPath + "\" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new");
        }
    }

    private RepositoryAccessException classifyFailure(String output) {
        String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        boolean authRelated = lower.contains("authentication failed")
                || lower.contains("could not read username")
                || lower.contains("terminal prompts disabled")
                || lower.contains("permission denied")
                || lower.contains("invalid username or password")
                || lower.contains("repository not found")
                || lower.contains("returned error: 401") || lower.contains("returned error: 403")
                || lower.contains("http basic: access denied");
        if (authRelated) {
            return new RepositoryAccessException(
                    Code.REMOTE_AUTH_FAILED, "Remote repository credentials were rejected");
        }
        return new RepositoryAccessException(Code.REMOTE_FETCH_FAILED, "Remote repository could not be fetched");
    }

    private Thread drainOutput(InputStream input, StringBuilder captured) {
        Thread drainer = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                for (int read; (read = input.read(buffer)) != -1;) {
                    synchronized (captured) {
                        if (captured.length() < CAPTURED_OUTPUT_LIMIT) {
                            captured.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IOException ignored) {
                // The stream closes when the Git process exits; partial output is still usable.
            }
        }, "remote-repository-git-output");
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    private void cleanStaleStaging() {
        if (!Files.isDirectory(mirrorsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant threshold = Instant.now().minus(STALE_STAGING_RETENTION);
        try (Stream<Path> entries = Files.list(mirrorsRoot)) {
            entries.filter(path -> path.getFileName().toString().startsWith(".mirror-staging-"))
                    .filter(path -> lastModified(path).isBefore(threshold))
                    .forEach(this::deleteDirectoryQuietly);
        } catch (IOException ignored) {
            // A later materialization can reconcile leftover staging directories.
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    private Path mirrorDirectory(String repositoryId) {
        return mirrorsRoot.resolve(sha256(repositoryId)).normalize();
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
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
                    // A later materialization can reconcile a partially removed mirror.
                }
            });
        } catch (IOException ignored) {
            // A later materialization can reconcile a partially removed mirror.
        }
    }

    private String truncate(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512) + "...";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte entry : bytes) {
                result.append(String.format("%02x", entry));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
