package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.application.ContextScoutConclusionService;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.agentscope.ContextScoutHarnessFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewRepositoryToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * [AIREVIEW-PLAN-025] Builds the real Context Scout Harness (no mocked builder path) so the
 * production builder configuration stays covered; previously only mocked factory tests existed
 * and a builder-side regression degraded every live review silently.
 *
 * @author wangli
 */
class ContextScoutHarnessFactoryTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsTheReadOnlyScoutHarnessAgainstARealSnapshotRoot() throws Exception {
        Path snapshotRoot = temporaryDirectory.resolve("snapshot-repository");
        Files.createDirectories(snapshotRoot);
        Files.writeString(snapshotRoot.resolve("README.md"), "# easy-query\n");
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                reviewId, 1, "user-001", "trace-001", IntakeCancellation.neverCancelled());

        ReviewRepositoryToolFactory toolFactory = mock(ReviewRepositoryToolFactory.class);
        when(toolFactory.requireSnapshot(any())).thenReturn(new RepositorySnapshot(
                UUID.randomUUID(),
                reviewId,
                "remote:test",
                snapshotRoot,
                snapshotRoot,
                "abc123",
                "main",
                false,
                "f".repeat(64),
                1,
                Instant.now()));
        when(toolFactory.sharedProjectContext(any())).thenReturn(new ReviewRepositoryToolFactory.SharedProjectContext(
                "remote:test",
                "abc123",
                "main",
                1,
                List.of("# Requirement", "Validate the scout harness build."),
                List.of(),
                List.of("README.md")));

        ReviewWorkspaceLayout layout = new ReviewWorkspaceLayout(
                new ReviewProperties(temporaryDirectory.toString(), 8, 2), new ObjectMapper());
        ContextScoutHarnessFactory factory = new ContextScoutHarnessFactory(
                mock(ModelGateway.class),
                new AgentScopeProperties(false, temporaryDirectory.resolve("state").toString()),
                toolFactory,
                new ObjectMapper(),
                layout,
                mock(ContextScoutConclusionService.class));

        ContextScoutHarnessFactory.ScoutRuntime runtime =
                factory.createRuntime(context, layout.open(context));

        assertThat(runtime.agent()).isNotNull();
        assertThat(runtime.toolTraceCollector()).isNotNull();
        runtime.agent().close();
    }
}
