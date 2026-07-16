package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the fixed review workspace topology and public artifact integrity envelope.
 *
 * @author wangli
 */
class ReviewWorkspaceLayoutTests {

    @TempDir
    Path root;

    @Test
    void createsFixedAttemptPathsAndWritesHashedPublicArtifact() throws Exception {
        ReviewWorkspaceLayout layout = new ReviewWorkspaceLayout(
                new ReviewProperties(root.toString(), 8, 2), new ObjectMapper());
        ReviewRuntimeContext context = context();

        ReviewWorkspaceLayout.ReviewWorkspace workspace = layout.open(context);
        ReviewWorkspaceLayout.WorkspaceArtifact artifact = layout.writeArtifact(
                workspace,
                ReviewWorkspaceLayout.ArtifactArea.PLANS,
                "plan-v1.json",
                "review public plan",
                context);

        assertThat(workspace.reviewRoot()).startsWith(root.toAbsolutePath());
        assertThat(Files.isDirectory(workspace.input())).isTrue();
        assertThat(Files.isDirectory(workspace.snapshot())).isTrue();
        assertThat(Files.isDirectory(workspace.plans())).isTrue();
        assertThat(Files.isDirectory(workspace.roles())).isTrue();
        assertThat(layout.roleWorkspace(workspace, RoleType.PRODUCT))
                .isNotEqualTo(layout.roleWorkspace(workspace, RoleType.BACKEND));
        assertThat(artifact.schemaVersion()).isEqualTo(1);
        assertThat(artifact.sha256()).hasSize(64);
        assertThat(new ObjectMapper().readTree(workspace.plans().resolve("plan-v1.json").toFile())
                .path("publicPayload").asText()).isEqualTo("review public plan");
    }

    @Test
    void rejectsAgentSelectedTraversalInArtifactName() {
        ReviewWorkspaceLayout layout = new ReviewWorkspaceLayout(
                new ReviewProperties(root.toString(), 8, 2), new ObjectMapper());
        ReviewWorkspaceLayout.ReviewWorkspace workspace = layout.open(context());

        assertThatThrownBy(() -> layout.writeArtifact(
                workspace,
                ReviewWorkspaceLayout.ArtifactArea.REPORTS,
                "../outside.json",
                "payload",
                context()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReviewRuntimeContext context() {
        return new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "reviewer-001", "trace-001", IntakeCancellation.neverCancelled());
    }
}
