package ai.cc.chongming.review.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Administrator-controlled repository identities and roots available to review jobs.
 * <p>
 * [AIREVIEW-PLAN-023#2] Keeps repository roots server-side while exposing safe display metadata.
 *
 * @author zyj
 */
@Validated
@ConfigurationProperties(prefix = "review.repositories")
public record RepositoryAccessProperties(@Valid List<RepositoryDefinition> allowed) {

    public RepositoryAccessProperties {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
        Set<String> repositoryIds = new HashSet<>();
        for (RepositoryDefinition repository : allowed) {
            if (!repositoryIds.add(repository.id())) {
                throw new IllegalArgumentException("duplicate repository id: " + repository.id());
            }
        }
    }

    /**
     * Maps one opaque repository identity to one administrator-configured local root.
     * <p>
     * [AIREVIEW-PLAN-023#2] Adds a public label without weakening the administrator-owned root boundary.
     *
     * @author zyj
     */
    public record RepositoryDefinition(@NotBlank String id, @NotBlank String root, String displayName) {

        public RepositoryDefinition {
            id = normalizeRequired(id, "id");
            root = normalizeRequired(root, "root");
            displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        }

        private static String normalizeRequired(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }
    }
}
