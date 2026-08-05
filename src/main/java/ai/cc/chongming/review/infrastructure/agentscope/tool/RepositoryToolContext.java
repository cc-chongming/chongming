package ai.cc.chongming.review.infrastructure.agentscope.tool;

import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.util.Objects;
import java.util.Set;

/**
 * Server-issued identity and frozen-snapshot scope required by every repository tool call.
 *
 * @author wangli
 */
public record RepositoryToolContext(
        String runtimeId,
        ReviewId reviewId,
        RoleType roleType,
        RepositorySnapshot snapshot,
        Set<String> allowedPathPrefixes) {

    public RepositoryToolContext {
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtimeId must not be blank");
        }
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!reviewId.equals(snapshot.reviewId())) {
            throw new IllegalArgumentException("Tool context reviewId must match the repository snapshot");
        }
        allowedPathPrefixes = allowedPathPrefixes == null || allowedPathPrefixes.isEmpty()
                ? Set.of("") : Set.copyOf(allowedPathPrefixes);
        if (allowedPathPrefixes.stream().anyMatch(prefix -> prefix == null || prefix.startsWith("/") || prefix.contains(".."))) {
            throw new IllegalArgumentException("allowedPathPrefixes must be safe snapshot-relative prefixes");
        }
    }

    public RepositoryToolContext(String runtimeId, ReviewId reviewId, RoleType roleType, RepositorySnapshot snapshot) {
        this(runtimeId, reviewId, roleType, snapshot, Set.of(""));
    }

    /**
     * Directory prefixes match at any directory boundary so multi-module layouts
     * (e.g. {@code ai-app/module/src/main/java/...}) stay inside a role scope such as {@code src/main/}.
     * Exact file prefixes (README.md, pom.xml) remain root-anchored.
     */
    public boolean allows(String relativePath) {
        String safePath = normalizeRelativePath(relativePath);
        return allowedPathPrefixes.stream().anyMatch(prefix -> prefix.isEmpty()
                || safePath.equals(prefix)
                || (prefix.endsWith("/") && (safePath.startsWith(prefix) || safePath.contains("/" + prefix))));
    }

    public String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("snapshot-relative path is required");
        }
        String value = relativePath.replace('\\', '/');
        if (value.startsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("snapshot-relative path is unsafe");
        }
        for (String segment : value.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("snapshot-relative path is unsafe");
            }
        }
        return value;
    }
}
