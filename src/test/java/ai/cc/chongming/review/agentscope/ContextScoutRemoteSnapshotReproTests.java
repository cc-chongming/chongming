package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.cc.chongming.review.application.ContextScoutConclusionService;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.RemoteTokenCipher;
import ai.cc.chongming.review.application.RepositorySnapshotService;
import ai.cc.chongming.review.application.ReviewIntakeService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.config.RepositoryAccessProperties;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.ContextScoutHarnessFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewRepositoryToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.infrastructure.agentscope.tool.ReadOnlyRepositoryTools;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementParser;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementValidator;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import ai.cc.chongming.review.infrastructure.repository.GitSnapshotReader;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryMaterializer;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryUrlValidator;
import ai.cc.chongming.review.infrastructure.repository.RepositoryBoundaryGuard;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * [AIREVIEW-PLAN-025] Diagnostic replay of the Context Scout preparation chain against the live
 * local workspace so a remote-source regression surfaces with its full stack trace instead of a
 * silent degradation. Skips automatically when the live workspace is absent (CI).
 *
 * @author wangli
 */
class ContextScoutRemoteSnapshotReproTests {

    private static final Path WORKSPACE = Path.of(".agentscope", "workspace").toAbsolutePath().normalize();
    private static final String REVIEW_ID = "299e7ab1-2702-43c3-b21f-666fc4a49ad1";

    static boolean liveWorkspacePresent() {
        return Files.isDirectory(WORKSPACE.resolve("reviews").resolve(REVIEW_ID));
    }

    @Test
    @EnabledIf("liveWorkspacePresent")
    void replaysScoutPreparationAgainstTheLiveRemoteSnapshot() {
        ReviewProperties properties = new ReviewProperties(WORKSPACE.toString(), 8, 2);
        ReviewWorkspaceLayout layout = new ReviewWorkspaceLayout(properties, new ObjectMapper());
        ReviewIntakeService intakeService = new ReviewIntakeService(
                new MarkdownRequirementValidator(),
                new MarkdownRequirementParser(),
                new RequirementSnapshotStore(properties),
                ReviewRegistry.noop());
        RemoteRepositoryMaterializer materializer = new RemoteRepositoryMaterializer(
                properties, new RemoteRepositoryUrlValidator(true, false));
        RepositorySnapshotService snapshotService = new RepositorySnapshotService(
                new RepositoryBoundaryGuard(new RepositoryAccessProperties(null, true, false), materializer),
                new GitSnapshotReader(),
                properties,
                materializer,
                new RemoteTokenCipher("chongming-local-remote-token-key"));
        ReviewRepositoryToolFactory toolFactory = new ReviewRepositoryToolFactory(
                intakeService, snapshotService, new ReadOnlyRepositoryTools(new RepositorySearchIndex()));
        ContextScoutHarnessFactory scoutFactory = new ContextScoutHarnessFactory(
                mock(ModelGateway.class),
                new AgentScopeProperties(false, WORKSPACE.resolve("state").toString()),
                toolFactory,
                new ObjectMapper(),
                layout,
                mock(ContextScoutConclusionService.class));
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                new ReviewId(UUID.fromString(REVIEW_ID)), 1, "kaifa99", "trace-repro",
                IntakeCancellation.neverCancelled());

        ContextScoutHarnessFactory.ScoutRuntime runtime =
                scoutFactory.createRuntime(context, layout.open(context));

        assertThat(runtime.agent()).isNotNull();
        runtime.agent().close();
    }
}
