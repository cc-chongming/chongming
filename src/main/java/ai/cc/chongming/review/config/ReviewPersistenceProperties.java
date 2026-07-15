package ai.cc.chongming.review.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the optional MySQL-backed persistence layer.
 *
 * @author wangli
 */
@Validated
@ConfigurationProperties(prefix = "review.persistence")
public record ReviewPersistenceProperties(
        boolean enabled,
        @NotBlank String jdbcUrl,
        @NotBlank String username,
        String password,
        @Min(1) int maximumPoolSize,
        @Valid @NotNull AgentScopeMysqlProperties agentscope) {

    /**
     * AgentScope-specific tables and distributed lock settings.
     *
     * @author wangli
     */
    public record AgentScopeMysqlProperties(
            @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String databaseName,
            @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String stateTableName,
            @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String workspaceTableName,
            @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String snapshotTableName,
            @NotBlank String lockKeyPrefix,
            boolean initializeSchema) {
    }
}
