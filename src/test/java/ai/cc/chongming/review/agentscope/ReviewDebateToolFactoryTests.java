package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewDebateToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkflowDispatcher;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateTools;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-021#10][AIREVIEW-PLAN-024#方案3/方案4] Verifies the model receives authoritative
 * persisted debate identifiers, deterministic conflict candidates, and that the Director steers
 * roles through dispatch_debate_action and registers topics in one batch.
 *
 * @author wangli
 */
class ReviewDebateToolFactoryTests {

    @Test
    void exposesPersistedInventoriesAndNoConflictRouteToAuthorizedRoles() {
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                mock(ReviewRegistry.class), mock(DebateTools.class), mock(DebateService.class),
                mock(ReviewDebateStore.class));
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "test-user", "test-trace", IntakeCancellation.neverCancelled());

        assertThat(factory.directorTools(context))
                .extracting(tool -> tool.getName())
                .containsExactly("list_persisted_claims", "list_conflict_candidates",
                        "list_persisted_debate_topics", "register_topics",
                        "dispatch_debate_action", "close_debate_topic", "begin_second_debate_round",
                        "begin_judging", "skip_debate_when_no_conflicts");
        assertThat(factory.roleTools(context, ai.cc.chongming.review.domain.model.ReviewTypes.RoleType.PRODUCT))
                .extracting(tool -> tool.getName())
                .startsWith("list_persisted_debate_topics");
        assertThat(factory.judgeTools(context))
                .extracting(tool -> tool.getName())
                .containsExactly("list_persisted_debate_topics", "submit_judgement", "draft_gate");
    }

    /**
     * [DEFENSE] The Director's dispatch tool must advertise the DEFENSE action in its schema and
     * description so the model-facing gateway never rejects the action at parse time.
     */
    @Test
    void dispatchDebateActionSchemaAllowsTheDefenseAction() {
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                mock(ReviewRegistry.class), mock(DebateTools.class), mock(DebateService.class),
                mock(ReviewDebateStore.class));
        io.agentscope.core.tool.AgentTool dispatchTool = factory.directorTools(new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "test-user", "test-trace", IntakeCancellation.neverCancelled()))
                .stream()
                .filter(tool -> tool.getName().equals("dispatch_debate_action"))
                .findFirst()
                .orElseThrow();

        assertThat(String.valueOf(dispatchTool.getParameters()))
                .as("dispatch_debate_action allowedAction schema must include DEFENSE")
                .contains("DEFENSE");
        assertThat(dispatchTool.getDescription()).contains("DEFENSE");
    }

    /** [AIREVIEW-PLAN-024#方案3] With the dispatch service wired, every role write tool demands a commandId. */
    @Test
    void roleWriteToolsRequireAnAuthorizingDispatchCommandIdWhenDispatchServiceIsWired() {
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                mock(ReviewRegistry.class), mock(DebateTools.class), mock(DebateService.class),
                mock(ReviewDebateStore.class),
                mock(ai.cc.chongming.review.application.ReviewDispatchService.class));
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "test-user", "test-trace", IntakeCancellation.neverCancelled());

        List<String> writeTools = List.of(
                "submit_challenge", "submit_rebuttal", "change_claim_position", "request_additional_evidence");
        factory.roleTools(context, ai.cc.chongming.review.domain.model.ReviewTypes.RoleType.PRODUCT).stream()
                .filter(tool -> writeTools.contains(tool.getName()))
                .forEach(tool -> {
                    Object schema = tool.getParameters();
                    assertThat(String.valueOf(schema))
                            .as("tool %s must demand a dispatch commandId", tool.getName())
                            .contains("commandId");
                });
    }
}
