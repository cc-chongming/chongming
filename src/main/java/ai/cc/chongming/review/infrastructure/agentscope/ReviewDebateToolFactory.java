package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateTools;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-009#1.4] Binds debate and Judge operations to the active review attempt.
 * The model never supplies review identity, actor identity, optimistic version, or idempotency.
 *
 * @author wangli
 */
@Component
public class ReviewDebateToolFactory {

    private final ReviewRegistry reviewRegistry;
    private final DebateTools debateTools;
    private final DebateService debateService;
    private final ReviewWorkflowDispatcher workflowDispatcher;

    public ReviewDebateToolFactory(
            ReviewRegistry reviewRegistry,
            DebateTools debateTools,
            DebateService debateService,
            ReviewWorkflowDispatcher workflowDispatcher) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.debateTools = Objects.requireNonNull(debateTools, "debateTools must not be null");
        this.debateService = Objects.requireNonNull(debateService, "debateService must not be null");
        this.workflowDispatcher = Objects.requireNonNull(workflowDispatcher, "workflowDispatcher must not be null");
    }

    public List<AgentTool> directorTools(ReviewRuntimeContext context) {
        return List.of(new OpenTopicTool(context), new CloseTopicTool(context), new BeginSecondRoundTool(context), new BeginJudgingTool(context));
    }

    public List<AgentTool> roleTools(ReviewRuntimeContext context, RoleType roleType) {
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE) {
            throw new IllegalArgumentException("only review roles may receive debate turn tools");
        }
        return List.of(new ChallengeTool(context, roleType), new RebuttalTool(context, roleType),
                new PositionChangeTool(context, roleType), new EvidenceRequestTool(context, roleType));
    }

    public List<AgentTool> judgeTools(ReviewRuntimeContext context) {
        return List.of(new JudgeTool(context), new DraftGateTool(context));
    }

    private abstract class BoundTool implements AgentTool {
        private final ReviewRuntimeContext context;
        private final RoleType actorRole;

        private BoundTool(ReviewRuntimeContext context, RoleType actorRole) {
            this.context = Objects.requireNonNull(context, "runtimeContext must not be null");
            this.actorRole = Objects.requireNonNull(actorRole, "actorRole must not be null");
        }

        @Override
        public final Boolean getStrict() {
            return true;
        }

        @Override
        public final Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromSupplier(() -> {
                Review review = requireReview(context);
                synchronized (review) {
                    return invoke(review, metadata(review, actorRole, param), param.getInput());
                }
            }).onErrorResume(exception -> Mono.just(ToolResultBlock.error("review workflow tool rejected")));
        }

        abstract ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input);

        final RoleType actor() {
            return actorRole;
        }
    }

    private final class OpenTopicTool extends BoundTool {
        private OpenTopicTool(ReviewRuntimeContext context) { super(context, RoleType.DIRECTOR); }
        @Override public String getName() { return "open_debate_topic"; }
        @Override public String getDescription() { return "Open one conflict topic from at least two persisted Claims during conflict detection."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of(
                "subjectKey", stringSchema("Conflicting public subject key"),
                "claimIds", idArraySchema("Persisted Claim UUIDs")), List.of("subjectKey", "claimIds")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            DebateService.TopicResult result = debateTools.openDebateTopic(review, new DebateToolCommands.OpenTopic(
                    metadata, RoleType.DIRECTOR, text(input, "subjectKey"), claimIds(input.get("claimIds"))));
            return ToolResultBlock.text("topicId=" + result.topic().id().value() + "; replayed=" + result.replayed());
        }
    }

    private final class CloseTopicTool extends BoundTool {
        private CloseTopicTool(ReviewRuntimeContext context) { super(context, RoleType.DIRECTOR); }
        @Override public String getName() { return "close_debate_topic"; }
        @Override public String getDescription() { return "Close a current debate topic as RESOLVED or ESCALATED with a public reason."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of(
                "topicId", stringSchema("Debate topic UUID"), "status", enumSchema("RESOLVED", "ESCALATED"),
                "publicResolution", stringSchema("Public resolution")), List.of("topicId", "status", "publicResolution")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            DebateService.TopicResult result = debateTools.closeDebateTopic(review, new DebateToolCommands.CloseTopic(metadata,
                    topicId(input), DebateTopicStatus.valueOf(text(input, "status")), text(input, "publicResolution")));
            return ToolResultBlock.text("topicId=" + result.topic().id().value() + "; replayed=" + result.replayed());
        }
    }

    private final class BeginSecondRoundTool extends BoundTool {
        private BeginSecondRoundTool(ReviewRuntimeContext context) { super(context, RoleType.DIRECTOR); }
        @Override public String getName() { return "begin_second_debate_round"; }
        @Override public String getDescription() { return "Move the review from debate round one to the bounded second round."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of(), List.of()); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            requireNoInput(input);
            if (!review.commandResults().containsKey(metadata.idempotencyKey())) {
                debateService.validateBeginSecondRound(review);
            }
            boolean replayed = advanceStage(review, metadata, ReviewStage.DEBATE_ROUND_1, "begin-second-round", () -> debateService.beginSecondRound(review));
            if (!replayed) workflowDispatcher.dispatchRound(review, 2);
            return ToolResultBlock.text("stage=" + review.stage() + "; replayed=" + replayed);
        }
    }

    private final class BeginJudgingTool extends BoundTool {
        private BeginJudgingTool(ReviewRuntimeContext context) { super(context, RoleType.DIRECTOR); }
        @Override public String getName() { return "begin_judging"; }
        @Override public String getDescription() { return "Enter judging only after every debate topic is terminal."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of(), List.of()); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            requireNoInput(input);
            if (!review.commandResults().containsKey(metadata.idempotencyKey())) {
                debateService.validateBeginJudging(review);
            }
            boolean replayed = advanceStage(review, metadata, ReviewStage.DEBATE_ROUND_2, "begin-judging", () -> debateService.beginJudging(review));
            if (!replayed) workflowDispatcher.dispatchJudge(review);
            return ToolResultBlock.text("stage=" + review.stage() + "; replayed=" + replayed);
        }
    }

    private final class ChallengeTool extends BoundTool {
        private ChallengeTool(ReviewRuntimeContext context, RoleType actor) { super(context, actor); }
        @Override public String getName() { return "submit_challenge"; }
        @Override public String getDescription() { return "Submit a directed public challenge against an existing Claim."; }
        @Override public Map<String, Object> getParameters() { return turnSchema(Map.of("targetClaimId", stringSchema("Target Claim UUID"),
                "evidenceGap", stringSchema("Required when no evidenceIds are supplied")), List.of("targetRole", "topicId", "round", "targetClaimId", "publicContent")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            DebateService.TurnResult result = debateTools.submitChallenge(review, new DebateToolCommands.Challenge(metadata, actor(), role(input, "targetRole"),
                    topicId(input), integer(input, "round"), claimId(input, "targetClaimId"), text(input, "publicContent"), evidenceIds(input.get("evidenceIds")), optionalText(input, "evidenceGap")));
            return ToolResultBlock.text("turnId=" + result.turn().turnId().value() + "; replayed=" + result.replayed());
        }
    }

    private final class RebuttalTool extends BoundTool {
        private RebuttalTool(ReviewRuntimeContext context, RoleType actor) { super(context, actor); }
        @Override public String getName() { return "submit_rebuttal"; }
        @Override public String getDescription() { return "Submit a directed public rebuttal to an existing debate turn."; }
        @Override public Map<String, Object> getParameters() { return turnSchema(Map.of("targetTurnId", stringSchema("Target Turn UUID")),
                List.of("targetRole", "topicId", "round", "targetTurnId", "publicContent")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            DebateService.TurnResult result = debateTools.submitRebuttal(review, new DebateToolCommands.Rebuttal(metadata, actor(), role(input, "targetRole"),
                    topicId(input), integer(input, "round"), new TurnId(uuid(input, "targetTurnId")), text(input, "publicContent"), evidenceIds(input.get("evidenceIds"))));
            return ToolResultBlock.text("turnId=" + result.turn().turnId().value() + "; replayed=" + result.replayed());
        }
    }

    private final class PositionChangeTool extends BoundTool {
        private PositionChangeTool(ReviewRuntimeContext context, RoleType actor) { super(context, actor); }
        @Override public String getName() { return "change_claim_position"; }
        @Override public String getDescription() { return "Record a non-destructive position change for a Claim owned by this role."; }
        @Override public Map<String, Object> getParameters() { return turnSchema(Map.of("targetClaimId", stringSchema("Owned Claim UUID"),
                "stanceAfter", enumSchema("SUPPORT", "OPPOSE", "NEUTRAL")), List.of("topicId", "round", "targetClaimId", "stanceAfter", "publicContent")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            DebateService.TurnResult result = debateTools.changePosition(review, new DebateToolCommands.PositionChange(metadata, actor(), topicId(input), integer(input, "round"),
                    claimId(input, "targetClaimId"), ClaimPosition.valueOf(text(input, "stanceAfter")), text(input, "publicContent"), evidenceIds(input.get("evidenceIds"))));
            return ToolResultBlock.text("turnId=" + result.turn().turnId().value() + "; replayed=" + result.replayed());
        }
    }

    private final class EvidenceRequestTool extends BoundTool {
        private EvidenceRequestTool(ReviewRuntimeContext context, RoleType actor) { super(context, actor); }
        @Override public String getName() { return "request_additional_evidence"; }
        @Override public String getDescription() { return "Request missing evidence from the role that owns a Claim; this does not fabricate evidence."; }
        @Override public Map<String, Object> getParameters() { return turnSchema(Map.of("targetClaimId", stringSchema("Target Claim UUID")),
                List.of("targetRole", "topicId", "round", "targetClaimId", "publicContent")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            DebateService.TurnResult result = debateTools.requestAdditionalEvidence(review, new DebateToolCommands.EvidenceRequest(metadata, actor(), role(input, "targetRole"),
                    topicId(input), integer(input, "round"), claimId(input, "targetClaimId"), text(input, "publicContent")));
            return ToolResultBlock.text("turnId=" + result.turn().turnId().value() + "; replayed=" + result.replayed());
        }
    }

    private final class JudgeTool extends BoundTool {
        private JudgeTool(ReviewRuntimeContext context) { super(context, RoleType.JUDGE); }
        @Override public String getName() { return "submit_judgement"; }
        @Override public String getDescription() { return "Submit one Judge conclusion for a terminal debate topic using only its persisted Claims."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of("topicId", stringSchema("Terminal topic UUID"),
                "proposedGateResult", enumSchema("AI_PASS", "CONDITIONAL", "BLOCK", "RETURN", "HUMAN_REQUIRED"),
                "publicReasonSummary", stringSchema("Public reasoning summary"), "acceptedClaimIds", idArraySchema("Accepted Claim UUIDs"),
                "rejectedClaimIds", idArraySchema("Rejected Claim UUIDs")), List.of("topicId", "proposedGateResult", "publicReasonSummary")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            JudgeService.JudgeResult result = debateTools.submitJudgement(review, new JudgeService.JudgeSubmission(metadata, topicId(input),
                    GateResult.valueOf(text(input, "proposedGateResult")), text(input, "publicReasonSummary"), claimIds(input.get("acceptedClaimIds")), claimIds(input.get("rejectedClaimIds"))));
            return ToolResultBlock.text("topicId=" + result.decision().topicId().value() + "; replayed=" + result.replayed());
        }
    }

    private final class DraftGateTool extends BoundTool {
        private DraftGateTool(ReviewRuntimeContext context) { super(context, RoleType.JUDGE); }
        @Override public String getName() { return "draft_gate"; }
        @Override public String getDescription() { return "Draft the non-final AI Gate from all terminal Judge decisions."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of(), List.of()); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            requireNoInput(input); var gate = debateTools.draftGate(review);
            return ToolResultBlock.text("gateResult=" + gate.result() + "; status=" + gate.status());
        }
    }

    private Review requireReview(ReviewRuntimeContext context) {
        return reviewRegistry.find(context.reviewId()).filter(review -> review.attemptNo() == context.attemptNo())
                .orElseThrow(() -> new IllegalStateException("active review was not found"));
    }

    private ReviewCommandMetadata metadata(Review review, RoleType actorRole, ToolCallParam param) {
        String callId = param.getToolUseBlock() == null ? "" : param.getToolUseBlock().getId();
        if (callId.isBlank()) throw new IllegalArgumentException("tool call id is required");
        return new ReviewCommandMetadata(review.id(), review.version(), new IdempotencyKey("tool:" + review.id().value() + ":" + actorRole + ":" + callId));
    }

    private boolean advanceStage(
            Review review, ReviewCommandMetadata metadata, ReviewStage expectedStage, String reference, Runnable operation) {
        if (!review.id().equals(metadata.reviewId())) throw new IllegalArgumentException("review identity does not match tool runtime");
        if (review.commandResults().containsKey(metadata.idempotencyKey())) return true;
        if (review.stage() != expectedStage || review.version() != metadata.expectedVersion()) {
            throw new IllegalStateException("review stage or version does not permit this transition");
        }
        review.recordCommand(metadata, reference);
        operation.run();
        return false;
    }

    private static String text(Map<String, Object> input, String name) { Object value = input.get(name); if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(name + " is required"); return value.toString(); }
    private static String optionalText(Map<String, Object> input, String name) { Object value = input.get(name); return value == null ? "" : value.toString(); }
    private static int integer(Map<String, Object> input, String name) { Object value = input.get(name); if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " must be numeric"); return number.intValue(); }
    private static UUID uuid(Map<String, Object> input, String name) { return UUID.fromString(text(input, name)); }
    private static TopicId topicId(Map<String, Object> input) { return new TopicId(uuid(input, "topicId")); }
    private static ClaimId claimId(Map<String, Object> input, String name) { return new ClaimId(uuid(input, name)); }
    private static RoleType role(Map<String, Object> input, String name) { return RoleType.valueOf(text(input, name)); }
    private static List<ClaimId> claimIds(Object value) { return ids(value).stream().map(ClaimId::new).toList(); }
    private static List<EvidenceId> evidenceIds(Object value) { return ids(value).stream().map(EvidenceId::new).toList(); }
    private static List<UUID> ids(Object value) { if (value == null) return List.of(); if (!(value instanceof Collection<?> collection)) throw new IllegalArgumentException("IDs must be an array"); return collection.stream().map(Object::toString).map(UUID::fromString).toList(); }
    private static void requireNoInput(Map<String, Object> input) { if (!input.isEmpty()) throw new IllegalArgumentException("tool does not accept input"); }
    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) { return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false); }
    private static Map<String, Object> turnSchema(Map<String, Object> additional, List<String> required) { java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>(); properties.put("targetRole", enumSchema(RoleType.PRODUCT.name(), RoleType.PROJECT.name(), RoleType.FRONTEND.name(), RoleType.BACKEND.name())); properties.put("topicId", stringSchema("Debate topic UUID")); properties.put("round", Map.of("type", "integer", "enum", List.of(1, 2))); properties.put("publicContent", stringSchema("Public debate content")); properties.put("evidenceIds", idArraySchema("Evidence UUIDs")); properties.putAll(additional); return objectSchema(Map.copyOf(properties), required); }
    private static Map<String, Object> stringSchema(String description) { return Map.of("type", "string", "description", description); }
    private static Map<String, Object> idArraySchema(String description) { return Map.of("type", "array", "description", description, "items", Map.of("type", "string")); }
    private static Map<String, Object> enumSchema(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
}
