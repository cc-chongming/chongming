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
 * [AIREVIEW-PLAN-028] Materializes remote Git repositories into server-managed shallow mirrors
 * under {@code repository-mirrors/}. [AIREVIEW-PLAN-029] Two callers share the engine:
 * administrator-configured remote definitions (credentials through environment variables) and
 * requirement-supplied ad-hoc sources (a decrypted access token handed over for one command).
 * The first access clones {@code --depth 1 --single-branch}; later accesses reuse the mirror
 * through a bounded fetch and hard reset, and any corrupted mirror is replaced by a fresh clone.
 * Credentials are injected through process environment variables only, never through
 * command-line arguments or logs.
 *
 * @author wangli
 */
@Component
public class RemoteRepositoryMaterializer {

    private static final Logger log = LoggerFactory.getLogger(RemoteRepositoryMaterializer.class);
    private static final int CAPTURED_OUTPUT_LIMIT = 4096;
    private static final Duration STALE_STAGING_RETENTION = Duration.ofHours(1);
    private static final Duration ADHOC_CLONE_TIMEOUT = Duration.ofMinutes(10);

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
        urlValidator.requireSafe(remote.url());
        RemoteTarget target = new RemoteTarget(
                remote.url(), remote.ref(), remote.cloneTimeout(), resolveConfiguredCredentials(remote));
        return materialize(definition.id(), target);
    }

    /**
     * [AIREVIEW-PLAN-029] Ensures one requirement-supplied online repository exists locally as a
     * clean shallow worktree. The mirror is keyed by {@code url + ref} only, so credential
     * rotation never forks mirrors; the plain-text token is injected for the network command and
     * discarded afterwards.
     *
     * @param url        validated online repository URL
     * @param ref        optional branch, {@code null} keeps the remote default branch
     * @param plainToken optional decrypted access token, {@code null} for public repositories
     * @return canonical mirror worktree root ready for snapshot capture
     */
    public Path ensureAdhocMirror(String url, String ref, String plainToken) {
        if (url == null || url.isBlank()) {
            throw new RepositoryAccessException(Code.REPOSITORY_NOT_CONFIGURED, "Remote repository URL is required");
        }
        urlValidator.requireSafe(url);
        String effectiveRef = ref == null || ref.isBlank() ? null : ref.trim();
        ResolvedCredentials credentials = plainToken == null || plainToken.isBlank()
                ? null
                : new ResolvedCredentials(AuthType.HTTPS_TOKEN, plainToken);
        RemoteTarget target = new RemoteTarget(url.trim(), effectiveRef, ADHOC_CLONE_TIMEOUT, credentials);
        return materialize("adhoc:" + sha256(target.url() + '\u0000' + (effectiveRef == null ? "" : effectiveRef)), target);
    }

    private Path materialize(String identity, RemoteTarget target) {
        Object lock = repositoryLocks.computeIfAbsent(identity, ignored -> new Object());
        synchronized (lock) {
            Path mirror = mirrorDirectory(identity);
            if (isUsableMirror(mirror, target)) {
                try {
                    updateMirror(mirror, target);
                    return mirror.toRealPath();
                } catch (RepositoryAccessException exception) {
                    log.warn("REMOTE_REPOSITORY_MIRROR_UPDATE_FAILED identity={} code={}", identity, exception.code());
                    deleteDirectoryQuietly(mirror);
                } catch (IOException exception) {
                    log.warn("REMOTE_REPOSITORY_MIRROR_UPDATE_FAILED identity={}", identity, exception);
                    deleteDirectoryQuietly(mirror);
                }
            } else if (Files.exists(mirror, LinkOption.NOFOLLOW_LINKS)) {
                // Only an unpublished/incomplete mirror is ever removed; the retry below re-clones.
                deleteDirectoryQuietly(mirror);
            }
            cloneMirror(identity, mirror, target);
            try {
                return mirror.toRealPath();
            } catch (IOException exception) {
                throw new RepositoryAccessException(
                        Code.REMOTE_FETCH_FAILED, "Remote repository mirror is unreadable", exception);
            }
        }
    }

    /** Resolves administrator-configured credentials through their environment-variable indirection. */
    private ResolvedCredentials resolveConfiguredCredentials(Remote remote) {
        AuthType authType = remote.auth().type();
        if (authType == AuthType.HTTPS_TOKEN) {
            String token = System.getenv(remote.auth().tokenEnv());
            if (token == null || token.isBlank()) {
                throw new RepositoryAccessException(
                        Code.REMOTE_AUTH_FAILED, "Remote repository credentials are not configured");
            }
            return new ResolvedCredentials(AuthType.HTTPS_TOKEN, token);
        }
        if (authType == AuthType.SSH_KEY) {
            String keyPathEnvironment = remote.auth().keyPathEnv();
            String keyPath = keyPathEnvironment == null ? null : System.getenv(keyPathEnvironment);
            if (keyPath == null || keyPath.isBlank() || keyPath.contains("\"")) {
                throw new RepositoryAccessException(
                        Code.REMOTE_AUTH_FAILED, "Remote repository SSH key is not configured");
            }
            if (!Files.isRegularFile(Path.of(keyPath), LinkOption.NOFOLLOW_LINKS)) {
                throw new RepositoryAccessException(
                        Code.REMOTE_AUTH_FAILED, "Remote repository SSH key is not configured");
            }
            return new ResolvedCredentials(AuthType.SSH_KEY, keyPath);
        }
        return null;
    }

    private boolean isUsableMirror(Path mirror, RemoteTarget target) {
        if (!Files.isDirectory(mirror.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String originUrl = runCapture(mirror, target.credentials(), Duration.ofSeconds(10), false,
                "remote", "get-url", "origin");
        return target.url().equals(originUrl);
    }

    private void updateMirror(Path mirror, RemoteTarget target) throws IOException {
        String effectiveRef = target.ref() != null
                ? target.ref()
                : runCapture(mirror, target.credentials(), Duration.ofSeconds(10), false,
                        "symbolic-ref", "--short", "-q", "HEAD");
        if (effectiveRef == null || effectiveRef.isBlank()) {
            throw new RepositoryAccessException(Code.REMOTE_FETCH_FAILED, "Remote repository mirror has no branch");
        }
        runRequired(mirror, target.credentials(), target.timeout(), true,
                "fetch", "--depth", "1", "--force", "--quiet", "origin");
        runRequired(mirror, target.credentials(), target.timeout(), false,
                "checkout", "--force", "--quiet", "-B", effectiveRef, "origin/" + effectiveRef);
        runRequired(mirror, target.credentials(), Duration.ofMinutes(2), false, "clean", "-fdx", "--quiet");
    }

    private void cloneMirror(String identity, Path mirror, RemoteTarget target) {
        try {
            Files.createDirectories(mirrorsRoot);
            cleanStaleStaging();
            Path staging = Files.createTempDirectory(mirrorsRoot, ".mirror-staging-");
            try {
                List<String> arguments = new ArrayList<>(List.of(
                        "clone", "--depth", "1", "--single-branch", "--quiet"));
                if (target.ref() != null) {
                    arguments.add("--branch");
                    arguments.add(target.ref());
                }
                arguments.add("--");
                arguments.add(target.url());
                arguments.add(staging.toString());
                runGit(null, target.credentials(), target.timeout(), true, arguments);
                Files.createDirectories(mirror.getParent());
                moveDirectory(staging, mirror);
                log.info("REMOTE_REPOSITORY_MIRROR_CLONED identity={}", identity);
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
            Path workingDirectory, ResolvedCredentials credentials, Duration timeout,
            boolean networkCommand, String... arguments) {
        try {
            return runGit(workingDirectory, credentials, timeout, networkCommand, List.of(arguments));
        } catch (RepositoryAccessException exception) {
            return null;
        }
    }

    private void runRequired(
            Path workingDirectory, ResolvedCredentials credentials, Duration timeout,
            boolean networkCommand, String... arguments) {
        runGit(workingDirectory, credentials, timeout, networkCommand, List.of(arguments));
    }

    /**
     * Runs one narrowly scoped, non-interactive Git command with credential injection limited to
     * process environment variables. Output is drained concurrently and kept bounded so a large
     * transfer can never stall the wait loop.
     */
    private String runGit(
            Path workingDirectory, ResolvedCredentials credentials, Duration timeout,
            boolean networkCommand, List<String> arguments) {
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
        applyGitConfig(environment, networkCommand ? credentials : null);
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

    /**
     * Applies process-scoped Git configuration through {@code GIT_CONFIG_*} environment variables:
     * {@code core.longpaths} always (deep Java workspaces on Windows exceed the legacy 260-char
     * limit) and credential headers only for network commands with resolved credentials.
     */
    private void applyGitConfig(Map<String, String> environment, ResolvedCredentials credentials) {
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        keys.add("core.longpaths");
        values.add("true");
        if (credentials != null && credentials.type() == AuthType.HTTPS_TOKEN) {
            String basic = Base64.getEncoder().encodeToString(
                    ("x-access-token:" + credentials.secret()).getBytes(StandardCharsets.UTF_8));
            keys.add("http.extraheader");
            values.add("Authorization: Basic " + basic);
        } else if (credentials != null && credentials.type() == AuthType.SSH_KEY) {
            environment.put("GIT_SSH_COMMAND",
                    "ssh -i \"" + credentials.secret()
                            + "\" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new");
        }
        environment.put("GIT_CONFIG_COUNT", String.valueOf(keys.size()));
        for (int index = 0; index < keys.size(); index++) {
            environment.put("GIT_CONFIG_KEY_" + index, keys.get(index));
            environment.put("GIT_CONFIG_VALUE_" + index, values.get(index));
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
        return new RepositoryAccessException(Code.REMOTE_FETCH_FAILED,
                "Remote repository could not be fetched" + (output == null || output.isBlank() ? "" : ": " + truncate(output)));
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

    private Path mirrorDirectory(String identity) {
        return mirrorsRoot.resolve(sha256(identity)).normalize();
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

    /**
     * Unified materialization descriptor shared by configured and ad-hoc remote sources.
     *
     * @author wangli
     */
    private record RemoteTarget(String url, String ref, Duration timeout, ResolvedCredentials credentials) {
    }

    /**
     * One resolved credential channel; {@code secret} is either a plain-text token or a key path
     * and only ever travels through process environment variables.
     *
     * @author wangli
     */
    private record ResolvedCredentials(AuthType type, String secret) {
    }
}
