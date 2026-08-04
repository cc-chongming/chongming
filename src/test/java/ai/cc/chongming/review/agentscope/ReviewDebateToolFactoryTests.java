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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-021#10] Verifies the model receives authoritative persisted debate identifiers.
 *
 * @author wangli
 */
class ReviewDebateToolFactoryTests {

    @Test
    void exposesPersistedInventoriesAndNoConflictRouteToAuthorizedRoles() {
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                mock(ReviewRegistry.class), mock(DebateTools.class), mock(DebateService.class),
                mock(ReviewWorkflowDispatcher.class), mock(ReviewDebateStore.class));
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "test-user", "test-trace", IntakeCancellation.neverCancelled());

        assertThat(factory.directorTools(context))
                .extracting(tool -> tool.getName())
                .containsExactly("list_persisted_claims", "list_persisted_debate_topics", "open_debate_topic",
                        "close_debate_topic", "begin_second_debate_round", "begin_judging",
                        "skip_debate_when_no_conflicts");
        assertThat(factory.roleTools(context, ai.cc.chongming.review.domain.model.ReviewTypes.RoleType.PRODUCT))
                .extracting(tool -> tool.getName())
                .startsWith("list_persisted_debate_topics");
        assertThat(factory.judgeTools(context))
                .extracting(tool -> tool.getName())
                .containsExactly("list_persisted_debate_topics", "submit_judgement", "draft_gate");
    }
}
