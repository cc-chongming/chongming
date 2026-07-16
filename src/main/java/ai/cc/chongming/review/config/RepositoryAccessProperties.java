package ai.cc.chongming.review.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Administrator-controlled repository identities and roots available to review jobs.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.repositories")
public record RepositoryAccessProperties(@Valid List<RepositoryDefinition> allowed) {

    public RepositoryAccessProperties {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    /**
     * Maps one opaque repository identity to one administrator-configured local root.
     *
     * @author wangli
     */
    public record RepositoryDefinition(@NotBlank String id, @NotBlank String root) {
    }
}
