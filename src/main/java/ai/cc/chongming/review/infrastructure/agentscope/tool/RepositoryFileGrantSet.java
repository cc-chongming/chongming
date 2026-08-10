package ai.cc.chongming.review.infrastructure.agentscope.tool;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * [AIREVIEW-PLAN-024] Immutable, hash-indexed set of role file grants.
 *
 * <p>Grant checks are O(1): tools resolve a {@code fileRef} through a {@link HashMap} instead of
 * scanning paths, and result filtering asks {@link #containsPath(String)} per snapshot file.
 *
 * @author wangli
 */
public final class RepositoryFileGrantSet {

    private static final RepositoryFileGrantSet EMPTY = new RepositoryFileGrantSet(List.of());

    private final List<RepositoryFileGrant> grants;
    private final Map<String, RepositoryFileGrant> grantByFileRef;
    private final Map<String, String> fileRefByNormalizedPath;

    private RepositoryFileGrantSet(Collection<RepositoryFileGrant> grants) {
        Objects.requireNonNull(grants, "grants must not be null");
        Map<String, RepositoryFileGrant> byFileRef = new HashMap<>();
        Map<String, String> byPath = new HashMap<>();
        List<RepositoryFileGrant> ordered = new ArrayList<>(grants.size());
        for (RepositoryFileGrant grant : grants) {
            Objects.requireNonNull(grant, "grant must not be null");
            if (byFileRef.putIfAbsent(grant.fileRef(), grant) != null) {
                throw new IllegalArgumentException("fileRef must be unique within a grant set");
            }
            byPath.putIfAbsent(grant.normalizedPath(), grant.fileRef());
            ordered.add(grant);
        }
        this.grants = List.copyOf(ordered);
        this.grantByFileRef = byFileRef;
        this.fileRefByNormalizedPath = byPath;
    }

    public static RepositoryFileGrantSet of(Collection<RepositoryFileGrant> grants) {
        if (Objects.requireNonNull(grants, "grants must not be null").isEmpty()) {
            return EMPTY;
        }
        return new RepositoryFileGrantSet(grants);
    }

    public static RepositoryFileGrantSet empty() {
        return EMPTY;
    }

    /**
     * Resolves one unguessable fileRef to its server-side grant in O(1).
     */
    public Optional<RepositoryFileGrant> resolve(String fileRef) {
        if (fileRef == null || fileRef.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(grantByFileRef.get(fileRef));
    }

    /**
     * Returns the fileRef issued for one normalized path, if granted.
     */
    public Optional<String> fileRefFor(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(fileRefByNormalizedPath.get(normalizedPath));
    }

    /**
     * O(1) membership test used while filtering listing/search results.
     */
    public boolean containsPath(String normalizedPath) {
        return normalizedPath != null && fileRefByNormalizedPath.containsKey(normalizedPath);
    }

    /**
     * Keeps only grants issued for one role; other roles' fileRefs become unresolvable.
     */
    public RepositoryFileGrantSet forRole(RoleType roleType) {
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (isEmpty()) {
            return this;
        }
        List<RepositoryFileGrant> filtered = grants.stream()
                .filter(grant -> grant.roleType() == roleType)
                .toList();
        return filtered.size() == grants.size() ? this : RepositoryFileGrantSet.of(filtered);
    }

    /**
     * Keeps only grants bound to one review, attempt and role; cross-attempt fileRefs become
     * unresolvable even if a token were replayed.
     */
    public RepositoryFileGrantSet boundTo(ReviewId reviewId, int attemptNo, RoleType roleType) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (isEmpty()) {
            return this;
        }
        List<RepositoryFileGrant> filtered = grants.stream()
                .filter(grant -> grant.reviewId().equals(reviewId)
                        && grant.attemptNo() == attemptNo
                        && grant.roleType() == roleType)
                .toList();
        return filtered.size() == grants.size() ? this : RepositoryFileGrantSet.of(filtered);
    }

    public boolean isEmpty() {
        return grants.isEmpty();
    }

    public int size() {
        return grants.size();
    }

    public List<RepositoryFileGrant> grants() {
        return grants;
    }

    /**
     * Deterministically ordered granted paths, used for one-shot set intersection.
     */
    public Set<String> paths() {
        Set<String> paths = new LinkedHashSet<>();
        for (RepositoryFileGrant grant : grants) {
            paths.add(grant.normalizedPath());
        }
        return paths;
    }
}
