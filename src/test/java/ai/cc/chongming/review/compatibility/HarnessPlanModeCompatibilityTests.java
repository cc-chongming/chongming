package ai.cc.chongming.review.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins Plan Mode behavior against the AgentScope 2.0.0 runtime dependency.
 *
 * @author wangli
 */
class HarnessPlanModeCompatibilityTests {

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closesWorkspaceManagers() {
        openManagers.forEach(WorkspaceManager::close);
        openManagers.clear();
    }

    @Test
    void persistsPlanModeAndPlanFilePath(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = new PlanModeManager(workspaceManager(project, workspace), null);
        AgentState state = AgentState.builder().build();

        assertThat(manager.isPlanActive(state)).isFalse();
        assertThat(manager.enter(state)).isEqualTo("plans/PLAN.md");
        assertThat(manager.isPlanActive(state)).isTrue();
        assertThat(manager.writePlan(RuntimeContext.empty(), state, "# PLAN\n- validate runtime"))
                .isEqualTo("plans/PLAN.md");

        manager.exit(state);

        assertThat(manager.isPlanActive(state)).isFalse();
        assertThat(state.getPlanModeContext().getCurrentPlanFile()).isEqualTo("plans/PLAN.md");
    }

    private WorkspaceManager workspaceManager(Path project, Path workspace) {
        AbstractFilesystem filesystem = new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        WorkspaceManager manager = new WorkspaceManager(workspace, filesystem);
        openManagers.add(manager);
        return manager;
    }
}
