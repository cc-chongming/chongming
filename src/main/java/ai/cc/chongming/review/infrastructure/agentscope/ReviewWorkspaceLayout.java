package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Owns the fixed, attempt-scoped workspace layout and integrity envelopes used by AgentScope.
 *
 * @author wangli
 */
@Component
public class ReviewWorkspaceLayout {

    private static final int SCHEMA_VERSION = 1;

    private final Path workspaceRoot;
    private final ObjectMapper objectMapper;

    public ReviewWorkspaceLayout(ReviewProperties reviewProperties, ObjectMapper objectMapper) {
        Objects.requireNonNull(reviewProperties, "reviewProperties must not be null");
        this.workspaceRoot = Path.of(reviewProperties.workspaceRoot()).toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Creates and returns only server-derived workspace paths for one review attempt.
     */
    public ReviewWorkspace open(ReviewRuntimeContext context) {
        Objects.requireNonNull(context, "context must not be null");
        context.cancellation().checkCancelled();
        Path reviewRoot = resolveUnderRoot("reviews", context.reviewId().value().toString());
        Path attemptRoot = resolveUnder(reviewRoot, "attempts", Integer.toString(context.attemptNo()));
        ReviewWorkspace workspace = new ReviewWorkspace(
                reviewRoot,
                resolveUnder(reviewRoot, "input"),
                resolveUnder(reviewRoot, "snapshot"),
                attemptRoot,
                resolveUnder(attemptRoot, "plans"),
                resolveUnder(attemptRoot, "evidence"),
                resolveUnder(attemptRoot, "claims"),
                resolveUnder(attemptRoot, "debates"),
                resolveUnder(attemptRoot, "reports"),
                resolveUnder(attemptRoot, "roles"));
        try {
            for (Path path : workspace.requiredDirectories()) {
                context.cancellation().checkCancelled();
                Files.createDirectories(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize review workspace", exception);
        }
        return workspace;
    }

    /**
     * Returns the isolated role-private directory beneath an already opened attempt workspace.
     */
    public Path roleWorkspace(ReviewWorkspace workspace, RoleType roleType) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (roleType == RoleType.DIRECTOR) {
            throw new IllegalArgumentException("director does not have a role-private workspace");
        }
        Path rolePath = resolveUnder(workspace.roles(), roleType.name().toLowerCase(Locale.ROOT));
        try {
            Files.createDirectories(rolePath);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize role workspace", exception);
        }
        return rolePath;
    }

    /**
     * Writes a public workspace artifact with a schema version and SHA-256 integrity hash.
     */
    public WorkspaceArtifact writeArtifact(
            ReviewWorkspace workspace, ArtifactArea area, String filename, String publicPayload, ReviewRuntimeContext context) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(area, "area must not be null");
        requireFileName(filename);
        if (publicPayload == null) {
            throw new IllegalArgumentException("publicPayload must not be null");
        }
        Objects.requireNonNull(context, "context must not be null");
        context.cancellation().checkCancelled();

        WorkspaceArtifact artifact = new WorkspaceArtifact(
                SCHEMA_VERSION, sha256(publicPayload), Instant.now().toString(), publicPayload);
        Path target = resolveUnder(area.resolve(workspace), filename);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(artifact);
            Files.write(temporary, serialized);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return artifact;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write workspace artifact", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A later write may safely replace a stale temporary file.
            }
        }
    }

    private Path resolveUnderRoot(String... segments) {
        return resolveUnder(workspaceRoot, segments);
    }

    private Path resolveUnder(Path base, String... segments) {
        Path candidate = base;
        for (String segment : segments) {
            candidate = candidate.resolve(segment);
        }
        candidate = candidate.normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Workspace path escaped configured root");
        }
        return candidate;
    }

    private String sha256(String payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(hash.length * 2);
            for (byte byteValue : hash) {
                value.append(String.format("%02x", byteValue));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireFileName(String filename) {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")
                || filename.equals(".") || filename.equals("..")) {
            throw new IllegalArgumentException("filename must be a single safe name");
        }
    }

    /**
     * Fixed paths that represent public collaboration and private role roots.
     *
     * @author wangli
     */
    public record ReviewWorkspace(
            Path reviewRoot,
            Path input,
            Path snapshot,
            Path attempt,
            Path plans,
            Path evidence,
            Path claims,
            Path debates,
            Path reports,
            Path roles) {

        private Path[] requiredDirectories() {
            return new Path[]{reviewRoot, input, snapshot, attempt, plans, evidence, claims, debates, reports, roles};
        }
    }

    /**
     * Permitted public collaboration areas; no arbitrary directory can be selected by an agent.
     *
     * @author wangli
     */
    public enum ArtifactArea {
        PLANS,
        EVIDENCE,
        CLAIMS,
        DEBATES,
        REPORTS;

        private Path resolve(ReviewWorkspace workspace) {
            return switch (this) {
                case PLANS -> workspace.plans();
                case EVIDENCE -> workspace.evidence();
                case CLAIMS -> workspace.claims();
                case DEBATES -> workspace.debates();
                case REPORTS -> workspace.reports();
            };
        }
    }

    /**
     * Serialized public artifact envelope. The payload is never a source of final business truth.
     *
     * @author wangli
     */
    public record WorkspaceArtifact(int schemaVersion, String sha256, String writtenAt, String publicPayload) {
    }
}
