package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import io.agentscope.core.agent.RuntimeContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests the session boundary used by the standalone Context Scout preview.
 *
 * @author wangli
 */
class ContextScoutPreviewServiceTests {

    @Test
    void createsAnIsolatedSessionForEachPreviewRun() {
        ReviewRuntimeContext reviewContext = new ReviewRuntimeContext(
                new ReviewId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                2,
                "user-001",
                "trace-001",
                IntakeCancellation.neverCancelled());

        RuntimeContext runtimeContext = ContextScoutPreviewService.previewAgentContext(reviewContext, "preview-001");

        assertThat(runtimeContext.getUserId()).isEqualTo("user-001");
        assertThat(runtimeContext.getSessionId())
                .isEqualTo(reviewContext.runtimeId() + ":context-scout:preview-preview-001");
        assertThat(runtimeContext.get(ReviewRuntimeContext.class)).isSameAs(reviewContext);
    }
}
