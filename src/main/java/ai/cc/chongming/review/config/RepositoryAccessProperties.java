package ai.cc.chongming.review.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Administrator-controlled repository identities and roots available to review jobs.
 * <p>
 * [AIREVIEW-PLAN-023#2] Keeps repository roots server-side while exposing safe display metadata.
 * [AIREVIEW-PLAN-028] Entries may be local roots or administrator-configured remote Git URLs;
 * API callers still only ever supply the opaque repository ID.
 *
 * @author zyj
 */
@Validated
@ConfigurationProperties(prefix = "review.repositories")
public record RepositoryAccessProperties(
        @Valid List<RepositoryDefinition> allowed,
        Boolean allowInternal,
        Boolean allowFileScheme) {

    public RepositoryAccessProperties {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
        allowInternal = Boolean.TRUE.equals(allowInternal);
        allowFileScheme = Boolean.TRUE.equals(allowFileScheme);
        Set<String> repositoryIds = new HashSet<>();
        for (RepositoryDefinition repository : allowed) {
            if (!repositoryIds.add(repository.id())) {
                throw new IllegalArgumentException("duplicate repository id: " + repository.id());
            }
        }
    }

    /**
     * Maps one opaque repository identity to one administrator-configured source. Local entries
     * bind {@code root}; remote entries bind the {@code remote} block instead.
     * <p>
     * [AIREVIEW-PLAN-023#2] Adds a public label without weakening the administrator-owned root boundary.
     * [AIREVIEW-PLAN-028] Adds the LOCAL/REMOTE source type validated at startup.
     *
     * @author zyj
     */
    public record RepositoryDefinition(
            @NotBlank String id,
            String root,
            String displayName,
            RepositoryType type,
            @Valid Remote remote) {

        /** [AIREVIEW-PLAN-028] Where the repository content comes from. */
        public enum RepositoryType {
            LOCAL,
            REMOTE
        }

        public RepositoryDefinition {
            id = normalizeRequired(id, "id");
            RepositoryType effectiveType = type == null ? RepositoryType.LOCAL : type;
            if (effectiveType == RepositoryType.REMOTE) {
                if (remote == null) {
                    throw new IllegalArgumentException("remote block is required when type is remote: " + id);
                }
                root = null;
            } else {
                root = normalizeRequired(root, "root");
                remote = null;
            }
            type = effectiveType;
            displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        }

        private static String normalizeRequired(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }

        /**
         * [AIREVIEW-PLAN-028] Administrator-configured remote source. Credentials are only referenced
         * through environment-variable names; no secret value may appear in configuration.
         *
         * @author wangli
         */
        public record Remote(
                @NotBlank String url,
                String ref,
                @Valid Auth auth,
                Duration cloneTimeout) {

            /** Default upper bound for one clone or mirror update. */
            public static final Duration DEFAULT_CLONE_TIMEOUT = Duration.ofMinutes(10);

            public Remote {
                url = normalizeRequired(url, "url");
                ref = ref == null || ref.isBlank() ? null : ref.trim();
                auth = auth == null ? new Auth(null, null, null) : auth;
                cloneTimeout = cloneTimeout == null ? DEFAULT_CLONE_TIMEOUT : cloneTimeout;
                if (cloneTimeout.isZero() || cloneTimeout.isNegative()) {
                    throw new IllegalArgumentException("cloneTimeout must be positive");
                }
            }

            /**
             * [AIREVIEW-PLAN-028] Credential channel for one remote source; {@code token-env} and
             * {@code key-path-env} carry environment-variable names, never secret values.
             *
             * @author wangli
             */
            public record Auth(AuthType type, String tokenEnv, String keyPathEnv) {

                /** [AIREVIEW-PLAN-028] Supported credential channels. */
                public enum AuthType {
                    NONE,
                    HTTPS_TOKEN,
                    SSH_KEY
                }

                public Auth {
                    AuthType effectiveType = type == null ? AuthType.NONE : type;
                    tokenEnv = normalizeOptional(tokenEnv);
                    keyPathEnv = normalizeOptional(keyPathEnv);
                    if (effectiveType == AuthType.HTTPS_TOKEN && tokenEnv == null) {
                        throw new IllegalArgumentException("token-env is required for https-token auth");
                    }
                    if (effectiveType == AuthType.SSH_KEY && keyPathEnv == null) {
                        throw new IllegalArgumentException("key-path-env is required for ssh-key auth");
                    }
                    type = effectiveType;
                }
            }

            private static String normalizeRequired(String value, String name) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(name + " must not be blank");
                }
                return value.trim();
            }

            private static String normalizeOptional(String value) {
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
    }
}
