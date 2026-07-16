package ai.cc.chongming.review.infrastructure.repository;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Reads Git revision metadata through narrowly scoped, non-interactive Git commands.
 *
 * @author wangli
 */
@Component
public class GitSnapshotReader {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Reads the revision, branch and dirty flag without executing repository code.
     *
     * @param repositoryRoot canonical authorized repository root
     * @param cancellation cancellation signal
     * @return metadata recorded alongside the frozen filesystem copy
     */
    public GitMetadata read(Path repositoryRoot, IntakeCancellation cancellation) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        cancellation.checkCancelled();
        String head = runRequired(repositoryRoot, cancellation, "rev-parse", "--verify", "HEAD");
        String branch = runOptional(repositoryRoot, cancellation, "symbolic-ref", "--short", "-q", "HEAD")
                .orElse("DETACHED");
        boolean dirty = !runRequired(repositoryRoot, cancellation, "status", "--porcelain=v1").isBlank();
        return new GitMetadata(head, branch, dirty);
    }

    private String runRequired(Path repositoryRoot, IntakeCancellation cancellation, String... arguments) {
        return run(repositoryRoot, cancellation, arguments).orElseThrow(() ->
                new RepositoryAccessException(Code.GIT_METADATA_UNAVAILABLE, "Unable to read Git metadata"));
    }

    private Optional<String> runOptional(Path repositoryRoot, IntakeCancellation cancellation, String... arguments) {
        return run(repositoryRoot, cancellation, arguments);
    }

    private Optional<String> run(Path repositoryRoot, IntakeCancellation cancellation, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repositoryRoot.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        try {
            Process process = builder.start();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                cancellation.checkCancelled();
                if (Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    cancellation.checkCancelled();
                }
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancellation.checkCancelled();
            throw new RepositoryAccessException(Code.GIT_METADATA_UNAVAILABLE, "Git metadata read was interrupted", exception);
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.GIT_METADATA_UNAVAILABLE, "Git executable is unavailable", exception);
        }
    }

    /**
     * Immutable Git metadata captured before copying the repository files.
     *
     * @author wangli
     */
    public record GitMetadata(String headCommit, String branch, boolean dirty) {

        public GitMetadata {
            if (headCommit == null || headCommit.isBlank()) {
                throw new IllegalArgumentException("headCommit must not be blank");
            }
            if (branch == null || branch.isBlank()) {
                throw new IllegalArgumentException("branch must not be blank");
            }
        }
    }
}
