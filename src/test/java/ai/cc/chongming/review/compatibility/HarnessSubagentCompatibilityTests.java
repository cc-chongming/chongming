package ai.cc.chongming.review.compatibility;

import java.nio.file.Path;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the AgentScope Harness sub-agent factory contract used by review workers.
 *
 * @author wangli
 */
class HarnessSubagentCompatibilityTests {

    @TempDir
    Path workspace;

    @Test
    void harnessCreatesConfiguredSubagentFromFactory() {
        Model model = Mockito.mock(Model.class);
        Agent worker = Mockito.mock(Agent.class);

        try (HarnessAgent harness = HarnessAgent.builder()
                .name("review-orchestrator")
                .agentId("review-orchestrator")
                .model(model)
                .workspace(workspace)
                .enablePlanMode()
                .subagentFactory("requirements-reviewer", ignored -> worker)
                .build()) {
            assertThat(harness.getSubagentAgentManager().hasAgent("requirements-reviewer")).isTrue();
            assertThat(harness.getSubagentAgentManager()
                    .createAgentIfPresent("requirements-reviewer", RuntimeContext.empty()))
                    .containsSame(worker);

            harness.enterPlanMode("review-001", "orchestrator");
            assertThat(harness.isPlanModeActive("review-001", "orchestrator")).isTrue();
            harness.exitPlanMode("review-001", "orchestrator");
            assertThat(harness.isPlanModeActive("review-001", "orchestrator")).isFalse();
        }
    }
}
