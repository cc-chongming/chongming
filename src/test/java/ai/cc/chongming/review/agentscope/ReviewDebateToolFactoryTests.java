package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewDispatchService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import ai.cc.chongming.review.infrastructure.dispatch.InMemoryReviewDispatchStore;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewDebateToolFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkflowDispatcher;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateTools;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    /** [AIREVIEW-PLAN-047#1] 工具契约：begin_second_round 必须携带必填 topicId 且声明议题级语义。 */
    @Test
    void beginSecondDebateRoundSchemaRequiresTopicIdAndDescribesTopicLevelSemantics() {
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                mock(ReviewRegistry.class), mock(DebateTools.class), mock(DebateService.class),
                mock(ReviewDebateStore.class));
        io.agentscope.core.tool.AgentTool beginSecondRound = factory.directorTools(new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "test-user", "test-trace", IntakeCancellation.neverCancelled()))
                .stream()
                .filter(tool -> tool.getName().equals("begin_second_debate_round"))
                .findFirst()
                .orElseThrow();

        String schema = String.valueOf(beginSecondRound.getParameters());
        assertThat(schema)
                .as("begin_second_debate_round must require the topicId")
                .contains("topicId", "required");
        assertThat(beginSecondRound.getDescription())
                .as("begin_second_debate_round must describe topic-level semantics")
                .contains("ONE topic", "single DEBATE phase");
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

    /** [AIREVIEW-PLAN-059#7] 串行闸：非焦点议题的 dispatch 被排队提示拒绝；焦点议题正常签发。 */
    @Test
    void serialGateRejectsNonFocusDispatchAndAllowsTheFocusTopic() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.DEBATE, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", true)), Map.of());
        ReviewRegistry registry = mock(ReviewRegistry.class);
        when(registry.find(reviewId)).thenReturn(Optional.of(review));
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        TopicId first = new TopicId(UUID.randomUUID());
        TopicId second = new TopicId(UUID.randomUUID());
        if (first.value().compareTo(second.value()) > 0) {
            TopicId swap = first; first = second; second = swap;
        }
        store.saveTopic(new DebateTopic(first, reviewId, "focus.topic", List.of()));
        store.saveTopic(new DebateTopic(second, reviewId, "queued.topic", List.of()));
        InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
        ReviewDispatchService dispatchService = new ReviewDispatchService(dispatchStore, store, draft -> { });
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                registry, mock(DebateTools.class), mock(DebateService.class), store, dispatchService);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                reviewId, 1, "test-user", "test-trace", IntakeCancellation.neverCancelled());
        io.agentscope.core.tool.AgentTool dispatchTool = factory.directorTools(context).stream()
                .filter(tool -> tool.getName().equals("dispatch_debate_action"))
                .findFirst().orElseThrow();

        Map<String, Object> queued = Map.of("recipientRole", "PRODUCT", "allowedAction", "DEFENSE",
                "topicId", second.value().toString());
        dispatchTool.callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-queued", "dispatch_debate_action", queued))
                .input(queued).build()).block();
        assertThat(dispatchStore.findByReview(reviewId, 1)).isEmpty();

        Map<String, Object> focus = Map.of("recipientRole", "PRODUCT", "allowedAction", "DEFENSE",
                "topicId", first.value().toString());
        dispatchTool.callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-focus", "dispatch_debate_action", focus))
                .input(focus).build()).block();
        assertThat(dispatchStore.findByReview(reviewId, 1)).hasSize(1);
    }

    /** [AIREVIEW-PLAN-090#3] 成功签发 dispatch 信封后，块状结果经 emitter 回灌，公开 trace 可见 commandId。 */
    @Test
    void dispatchDebateActionStreamsTheCommandIdThroughTheToolEmitter() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.DEBATE, 1, 0,
                List.of(new RoleActivation(RoleType.PRODUCT, "product", true)), Map.of());
        ReviewRegistry registry = mock(ReviewRegistry.class);
        when(registry.find(reviewId)).thenReturn(Optional.of(review));
        InMemoryReviewDebateStore store = new InMemoryReviewDebateStore();
        TopicId focus = new TopicId(UUID.randomUUID());
        store.saveTopic(new DebateTopic(focus, reviewId, "focus.topic", List.of()));
        InMemoryReviewDispatchStore dispatchStore = new InMemoryReviewDispatchStore();
        ReviewDispatchService dispatchService = new ReviewDispatchService(dispatchStore, store, draft -> { });
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                registry, mock(DebateTools.class), mock(DebateService.class), store, dispatchService);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                reviewId, 1, "test-user", "test-trace", IntakeCancellation.neverCancelled());
        io.agentscope.core.tool.AgentTool dispatchTool = factory.directorTools(context).stream()
                .filter(tool -> tool.getName().equals("dispatch_debate_action"))
                .findFirst().orElseThrow();
        Map<String, Object> input = Map.of(
                "recipientRole", "PRODUCT", "allowedAction", "DEFENSE",
                "topicId", focus.value().toString());
        CaptureEmitter emitter = new CaptureEmitter();

        dispatchTool.callAsync(ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("call-focus", "dispatch_debate_action", input))
                        .input(input)
                        .emitter(emitter)
                        .build())
                .block();

        assertThat(emitter.emittedText()).contains("commandId=");
    }

    @Test
    void registerTopicsSchemaAdvertisesTheOptionalChinesePublicTitle() {
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                mock(ReviewRegistry.class), mock(DebateTools.class), mock(DebateService.class),
                mock(ReviewDebateStore.class));
        io.agentscope.core.tool.AgentTool registerTopics = factory.directorTools(new ReviewRuntimeContext(
                new ReviewId(UUID.randomUUID()), 1, "test-user", "test-trace", IntakeCancellation.neverCancelled()))
                .stream()
                .filter(tool -> tool.getName().equals("register_topics"))
                .findFirst()
                .orElseThrow();

        String schema = String.valueOf(registerTopics.getParameters());
        assertThat(schema)
                .as("register_topics must advertise the optional Chinese publicTitle")
                .contains("publicTitle", "简明中文标题", "建议不超过 20 字");
    }

    @Test
    void registerTopicsParsesTheChinesePublicTitleAndTruncatesOverlongTitles() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Review review = Review.restore(reviewId, ReviewStage.CONFLICT_DETECTION, 1, 4L, List.of(), Map.of());
        ReviewRegistry registry = mock(ReviewRegistry.class);
        when(registry.find(reviewId)).thenReturn(Optional.of(review));
        DebateTools debateTools = mock(DebateTools.class);
        when(debateTools.registerDebateTopics(any(), any()))
                .thenReturn(new DebateService.RegisterTopicsResult(List.of(), false));
        ReviewDebateToolFactory factory = new ReviewDebateToolFactory(
                registry, debateTools, mock(DebateService.class), mock(ReviewDebateStore.class));
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                reviewId, 1, "test-user", "test-trace", IntakeCancellation.neverCancelled());
        io.agentscope.core.tool.AgentTool registerTopics = factory.directorTools(context).stream()
                .filter(tool -> tool.getName().equals("register_topics"))
                .findFirst()
                .orElseThrow();
        Map<String, Object> input = Map.of("topics", List.of(Map.of(
                "subjectKey", "mcp.security",
                "claimIds", List.of(),
                "publicTitle", "辩".repeat(205))));

        registerTopics.callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-register", "register_topics", input))
                .input(input)
                .build()).block();

        ArgumentCaptor<DebateToolCommands.RegisterTopics> captor =
                ArgumentCaptor.forClass(DebateToolCommands.RegisterTopics.class);
        verify(debateTools).registerDebateTopics(any(), captor.capture());
        DebateToolCommands.TopicProposal proposal = captor.getValue().proposals().get(0);
        assertThat(proposal.publicTitle()).hasSize(200);
        assertThat(proposal.subjectKey()).isEqualTo("mcp.security");
        assertThat(proposal.claimIds()).isEmpty();
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
