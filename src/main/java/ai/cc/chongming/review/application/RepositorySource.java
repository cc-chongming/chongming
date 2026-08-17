package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.RemoteRepositorySource;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-029] Repository binding for one snapshot capture: either an
 * administrator-configured repository identity or a requirement-supplied online repository
 * source. Exactly one of the two components is present.
 *
 * @author wangli
 */
public record RepositorySource(String configuredRepositoryId, RemoteRepositorySource remoteSource) {

    public RepositorySource {
        if ((configuredRepositoryId == null) == (remoteSource == null)) {
            throw new IllegalArgumentException("exactly one repository binding must be present");
        }
    }

    /**
     * @param repositoryId administrator-configured repository identity
     * @return source resolved through the boundary guard whitelist
     */
    public static RepositorySource configured(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be blank");
        }
        return new RepositorySource(repositoryId, null);
    }

    /**
     * @param remoteSource requirement-supplied online repository source
     * @return source materialized into a server-managed mirror before capture
     */
    public static RepositorySource remote(RemoteRepositorySource remoteSource) {
        return new RepositorySource(null, Objects.requireNonNull(remoteSource, "remoteSource must not be null"));
    }

    /**
     * @param snapshot intake snapshot carrying the repository binding for one review attempt
     * @return source equivalent to the snapshot binding
     */
    public static RepositorySource from(RequirementSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return snapshot.remoteSource() == null
                ? configured(snapshot.repositoryPath())
                : remote(snapshot.remoteSource());
    }

    /**
     * @return stable identity used for shared snapshot directories and references
     */
    public String repositoryIdentity() {
        return configuredRepositoryId != null ? configuredRepositoryId : remoteSource.repositoryIdentity();
    }
}
