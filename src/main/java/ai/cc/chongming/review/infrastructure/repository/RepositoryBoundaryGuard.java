package ai.cc.chongming.review.infrastructure.repository;

import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import ai.cc.chongming.review.config.RepositoryAccessProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Resolves only administrator-configured repository identities to safe canonical roots.
 *
 * @author wangli
 */
@Component
public class RepositoryBoundaryGuard {

    private final Map<String, Path> configuredRoots;

    public RepositoryBoundaryGuard(RepositoryAccessProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        Map<String, Path> roots = new LinkedHashMap<>();
        for (RepositoryAccessProperties.RepositoryDefinition definition : properties.allowed()) {
            Path previous = roots.putIfAbsent(definition.id(), Path.of(definition.root()));
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate repository id: " + definition.id());
            }
        }
        this.configuredRoots = Map.copyOf(roots);
    }

    /**
     * Resolves an opaque repository ID without accepting a caller-controlled filesystem path.
     *
     * @param repositoryId administrator-configured repository identity
     * @return canonical repository root that passed boundary validation
     */
    public AuthorizedRepository requireAuthorized(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new RepositoryAccessException(Code.REPOSITORY_NOT_CONFIGURED, "repositoryId is required");
        }
        Path configuredRoot = configuredRoots.get(repositoryId);
        if (configuredRoot == null) {
            throw new RepositoryAccessException(Code.REPOSITORY_NOT_CONFIGURED, "Repository is not configured");
        }
        Path lexicalRoot = configuredRoot.toAbsolutePath().normalize();
        if (isUncPath(lexicalRoot) || containsLinkOrReparsePoint(lexicalRoot)) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Repository root is not a safe local path");
        }
        if (!Files.isDirectory(lexicalRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new RepositoryAccessException(Code.REPOSITORY_NOT_FOUND, "Configured repository root does not exist");
        }
        Path canonicalRoot;
        try {
            canonicalRoot = lexicalRoot.toRealPath();
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.REPOSITORY_NOT_FOUND, "Configured repository root is unreadable", exception);
        }
        verifyGitMetadata(canonicalRoot);
        return new AuthorizedRepository(repositoryId, canonicalRoot);
    }

    private void verifyGitMetadata(Path repositoryRoot) {
        Path gitDirectory = repositoryRoot.resolve(".git");
        if (Files.isSymbolicLink(gitDirectory) || isReparsePoint(gitDirectory)) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Repository Git metadata must not be linked");
        }
        if (!Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new RepositoryAccessException(Code.REPOSITORY_NOT_GIT, "Configured root is not a standalone Git repository");
        }
    }

    private boolean containsLinkOrReparsePoint(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || isReparsePoint(current))) {
                return true;
            }
        }
        return false;
    }

    private boolean isUncPath(Path path) {
        return path.toString().startsWith("\\\\");
    }

    private boolean isReparsePoint(Path path) {
        try {
            Object attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            return attributes instanceof Integer value && (value & 0x400) != 0;
        } catch (IOException | UnsupportedOperationException ignored) {
            return false;
        }
    }

    /**
     * Canonical repository identity used internally by snapshot services, never supplied by an API caller.
     *
     * @author wangli
     */
    public record AuthorizedRepository(String repositoryId, Path root) {

        public AuthorizedRepository {
            Objects.requireNonNull(repositoryId, "repositoryId must not be null");
            Objects.requireNonNull(root, "root must not be null");
        }
    }
}
