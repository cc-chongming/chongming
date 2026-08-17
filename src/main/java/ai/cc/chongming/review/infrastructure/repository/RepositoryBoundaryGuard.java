package ai.cc.chongming.review.infrastructure.repository;

import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import ai.cc.chongming.review.config.RepositoryAccessProperties;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.RepositoryType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves only administrator-configured repository identities to safe canonical roots.
 * <p>
 * [AIREVIEW-PLAN-028] Remote entries are materialized into server-managed mirrors first; the
 * resulting worktree then passes the same link and Git-metadata checks as a local root.
 *
 * @author wangli
 */
@Component
public class RepositoryBoundaryGuard {

    private final Map<String, RepositoryDefinition> configuredRepositories;
    private final RemoteRepositoryMaterializer remoteMaterializer;

    @Autowired
    public RepositoryBoundaryGuard(
            RepositoryAccessProperties properties, RemoteRepositoryMaterializer remoteMaterializer) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.remoteMaterializer = remoteMaterializer;
        this.configuredRepositories = configuredRepositories(properties);
    }

    /** [AIREVIEW-PLAN-028] Backward-compatible guard without remote repository support. */
    public RepositoryBoundaryGuard(RepositoryAccessProperties properties) {
        this(properties, null);
    }

    private static Map<String, RepositoryDefinition> configuredRepositories(RepositoryAccessProperties properties) {
        Map<String, RepositoryDefinition> repositories = new LinkedHashMap<>();
        for (RepositoryDefinition definition : properties.allowed()) {
            RepositoryDefinition previous = repositories.putIfAbsent(definition.id(), definition);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate repository id: " + definition.id());
            }
        }
        return Map.copyOf(repositories);
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
        RepositoryDefinition definition = configuredRepositories.get(repositoryId);
        if (definition == null) {
            throw new RepositoryAccessException(Code.REPOSITORY_NOT_CONFIGURED, "Repository is not configured");
        }
        if (definition.type() == RepositoryType.REMOTE) {
            return authorizeRemote(repositoryId, definition);
        }
        Path lexicalRoot = Path.of(definition.root()).toAbsolutePath().normalize();
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

    /**
     * [AIREVIEW-PLAN-028] Materializes one configured remote source and applies the same safety
     * checks to the managed mirror worktree as to an administrator-configured local root.
     */
    private AuthorizedRepository authorizeRemote(String repositoryId, RepositoryDefinition definition) {
        if (remoteMaterializer == null) {
            throw new RepositoryAccessException(
                    Code.REMOTE_FETCH_FAILED, "Remote repository support is not available");
        }
        Path mirrorRoot = remoteMaterializer.ensureMirror(definition);
        Path canonicalRoot = mirrorRoot.toAbsolutePath().normalize();
        if (isUncPath(canonicalRoot) || containsLinkOrReparsePoint(canonicalRoot)) {
            throw new RepositoryAccessException(
                    Code.REPOSITORY_PATH_UNSAFE, "Remote repository mirror is not a safe local path");
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
