package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.application.AssessmentService;
import ai.cc.chongming.review.application.ClaimService;
import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.EvidenceBlock;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewRoleToolFactory;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-090#3] Verifies write tools re-emit the same block-shaped result through the
 * ToolEmitter so the public trace can see the result text.
 *
 * @author wangli
 */
class ReviewRoleToolFactoryTests {

    @Test
    void submitClaimStreamsTheClaimIdThroughTheToolEmitter() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.INITIAL_REVIEW, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", false)), Map.of());
        ReviewRegistry registry = mock(ReviewRegistry.class);
        when(registry.find(reviewId)).thenReturn(Optional.of(review));

        EvidenceLedgerService evidenceLedgerService = mock(EvidenceLedgerService.class);
        when(evidenceLedgerService.findByIds(any(), any()))
                .thenReturn(Map.<EvidenceId, EvidenceBlock>of());
        ReviewDebateStore debateStore = new InMemoryReviewDebateStore();
        ClaimService claimService = new ClaimService(
                evidenceLedgerService, debateStore, new ReviewProtocolGuard());
        ReviewRoleToolFactory factory = new ReviewRoleToolFactory(
                registry,
                claimService,
                mock(InitialReviewProgressService.class),
                mock(AssessmentService.class),
                debateStore);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                reviewId, 1, "test-user", "test-trace", IntakeCancellation.neverCancelled());
        AgentTool submitClaim = factory.initialReviewTools(context, RoleType.PRODUCT).stream()
                .filter(tool -> tool.getName().equals("submit_claim"))
                .findFirst()
                .orElseThrow();
        Map<String, Object> input = Map.of(
                "subjectKey", "mcp.security",
                "severity", "P1",
                "position", "OPPOSE",
                "statement", "MCP 鉴权证据缺失",
                "reasonSummary", "未见服务端鉴权覆盖",
                "evidenceIds", List.of());
        CaptureEmitter emitter = new CaptureEmitter();

        ToolResultBlock block = submitClaim.callAsync(ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("call-claim", "submit_claim", input))
                        .input(input)
                        .emitter(emitter)
                        .build())
                .block();

        assertThat(block.getOutput().toString()).contains("claimId=");
        assertThat(emitter.emittedText()).contains("claimId=");
    }

    /** ToolEmitter 捕获实现：只记录 emit 到的 block 文本，供断言。 */
    private static final class CaptureEmitter implements ToolEmitter {
        private final List<String> emitted = new ArrayList<>();

        @Override
        public void emit(ToolResultBlock block) {
            emitted.add(block.getOutput().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(" ")));
        }

        String emittedText() {
            return String.join(" ", emitted);
        }
    }
}
