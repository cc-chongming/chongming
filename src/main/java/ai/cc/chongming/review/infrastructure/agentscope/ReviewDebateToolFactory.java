package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ConflictDetectionService;
import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.application.ReviewDispatchService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateToolCommands;
import ai.cc.chongming.review.infrastructure.agentscope.tool.DebateTools;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * [AIREVIEW-PLAN-009#1.4][AIREVIEW-PLAN-024#方案3/方案4] Binds debate and Judge operations to the
 * active review attempt. The model never supplies review identity, actor identity, optimistic
 * version, or idempotency; every debate write action must additionally reference a valid
 * server-issued dispatch commandId, and the Director steers roles through the dispatch tool
 * instead of broadcast text. Topic registration is batch ({@code register_topics}) and fed by the
 * deterministic {@code list_conflict_candidates} recall.
 *
 * @author wangli
 */
@Component
public class ReviewDebateToolFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewDebateToolFactory.class);

    private static final long DISPATCH_DEFAULT_TTL_SECONDS = 600;
    private static final long DISPATCH_MIN_TTL_SECONDS = 60;
    private static final long DISPATCH_MAX_TTL_SECONDS = 3600;

    private final ReviewRegistry reviewRegistry;
    private final DebateTools debateTools;
    private final DebateService debateService;
    private final ReviewDebateStore debateStore;
    private final ReviewDispatchService dispatchService;
    private final ConflictDetectionService conflictDetectionService;

    public ReviewDebateToolFactory(
            ReviewRegistry reviewRegistry,
            DebateTools debateTools,
            DebateService debateService,
            ReviewDebateStore debateStore) {
        this(reviewRegistry, debateTools, debateService, debateStore, null, null);
    }

    public ReviewDebateToolFactory(
            ReviewRegistry reviewRegistry,
            DebateTools debateTools,
            DebateService debateService,
            ReviewDebateStore debateStore,
            ReviewDispatchService dispatchService) {
        this(reviewRegistry, debateTools, debateService, debateStore, dispatchService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewDebateToolFactory(
            ReviewRegistry reviewRegistry,
            DebateTools debateTools,
            DebateService debateService,
            ReviewDebateStore debateStore,
            ReviewDispatchService dispatchService,
            ConflictDetectionService conflictDetectionService) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.debateTools = Objects.requireNonNull(debateTools, "debateTools must not be null");
        this.debateService = Objects.requireNonNull(debateService, "debateService must not be null");
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.dispatchService = dispatchService;
        this.conflictDetectionService = conflictDetectionService;
    }

    public List<AgentTool> directorTools(ReviewRuntimeContext context) {
        return List.of(new ListPersistedClaimsTool(context), new ListConflictCandidatesTool(context),
                new ListPersistedDebateTopicsTool(context, RoleType.DIRECTOR),
                new RegisterTopicsTool(context), new DispatchDebateActionTool(context), new CloseTopicTool(context),
                new BeginSecondRoundTool(context), new BeginJudgingTool(context), new SkipDebateWhenNoConflictsTool(context));
    }

    /**
     * Role debate tools. Write actions stay registered because the role toolkit is fixed at
     * registration time, but every write invocation is bound to one valid PENDING dispatch
     * command addressed to the invoking role (see {@link ReviewDispatchService#resolveForWrite});
     * without a valid commandId no write action can execute.
     */
    public List<AgentTool> roleTools(ReviewRuntimeContext context, RoleType roleType) {
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE) {
            throw new IllegalArgumentException("only review roles may receive debate turn tools");
        }
        return List.of(new ListPersistedDebateTopicsTool(context, roleType), new ChallengeTool(context, roleType),
                new RebuttalTool(context, roleType), new PositionChangeTool(context, roleType),
                new EvidenceRequestTool(context, roleType));
    }

    public List<AgentTool> judgeTools(ReviewRuntimeContext context) {
        return List.of(new ListPersistedDebateTopicsTool(context, RoleType.JUDGE), new JudgeTool(context), new DraftGateTool(context));
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
            }).onErrorResume(exception -> {
                String reason = rejectionReason(exception);
                LOGGER.warn("REVIEW_WORKFLOW_TOOL_REJECTED tool={} reviewId={} attempt={} reason={}",
                        getName(), context.reviewId().value(), context.attemptNo(), reason);
                return Mono.just(ToolResultBlock.error("review workflow tool rejected: " + reason));
            });
        }

        abstract ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input);

        private static String rejectionReason(Throwable exception) {
            if (exception instanceof ai.cc.chongming.review.domain.exception.ReviewDomainException domain) {
                return domain.errorCode() + ": " + domain.getMessage();
            }
            String message = exception.getMessage();
            return message == null || message.isBlank()
                    ? exception.getClass().getSimpleName() : message;
        }

        final RoleType actor() {
            return actorRole;
        }

        /**
         * [AIREVIEW-PLAN-024#方案3] Resolves the dispatch command a write action must reference.
         * Returns null only on the legacy path where no dispatch service is wired.
         */
        final ReviewDispatchCommand resolveCommand(Review review, Map<String, Object> input, DispatchedAction action) {
            if (dispatchService == null) {
                return null;
            }
            return dispatchService.resolveForWrite(review, actor(), new CommandId(uuid(input, "commandId")), action);
        }

        final void consumeCommand(Review review, ReviewDispatchCommand command) {
            if (dispatchService != null && command != null) {
                dispatchService.consume(review, command);
            }
        }

        /** Write tools require the authorizing commandId unless running on the legacy path. */
        final List<String> writeRequired(List<String> base) {
            if (dispatchService == null) {
                return base;
            }
            java.util.ArrayList<String> required = new java.util.ArrayList<>(base);
            required.add("commandId");
            return List.copyOf(required);
        }

        final Map<String, Object> commandIdSchema() {
            return Map.of("commandId", stringSchema("Dispatch command UUID authorizing exactly this write action"));
        }
    }

    /**
     * Exposes an authoritative, read-only Claim inventory to the Director during conflict detection.
     *
     * @author wangli
     */
    private final class ListPersistedClaimsTool extends BoundTool {

        private ListPersistedClaimsTool(ReviewRuntimeContext context) {
            super(context, RoleType.DIRECTOR);
        }

        @Override
        public String getName() {
            return "list_persisted_claims";
        }

        @Override
        public String getDescription() {
            return "List the authoritative persisted Claims and IDs before opening debate topics; this never changes review state.";
        }

        @Override
        public Map<String, Object> getParameters() {
            return objectSchema(Map.of(), List.of());
        }

        @Override
        ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            requireNoInput(input);
            List<Claim> claims = debateStore.findClaims(review.id());
            if (claims.isEmpty()) {
                return ToolResultBlock.text("claims=[]");
            }
            String payload = claims.stream()
                    .map(claim -> "claimId=" + claim.claimId().value()
                            + "; role=" + claim.roleType()
                            + "; subjectKey=" + sanitize(claim.subjectKey())
                            + "; severity=" + claim.severity()
                            + "; position=" + claim.position()
                            + "; status=" + claim.status()
                            + "; statement=" + sanitize(claim.statement())
                            + "; reason=" + sanitize(claim.reasonSummary()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            return ToolResultBlock.text(payload);
        }

        private String sanitize(String value) {
            return value.replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Read-only recall of the deterministic conflict candidates; the
     * Director must register its chosen topics from this list instead of inventing subjects.
     *
     * @author wangli
     */
    private final class ListConflictCandidatesTool extends BoundTool {

        private ListConflictCandidatesTool(ReviewRuntimeContext context) {
            super(context, RoleType.DIRECTOR);
        }

        @Override
        public String getName() {
            return "list_conflict_candidates";
        }

        @Override
        public String getDescription() {
            return "List deterministic conflict candidates (subjects with contradictory conclusions) plus Gate risk counts, computed from persisted Assessments and Claims; this never changes review state.";
        }

        @Override
        public Map<String, Object> getParameters() {
            return objectSchema(Map.of(), List.of());
        }

        @Override
        ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            requireNoInput(input);
            if (conflictDetectionService == null) {
                throw new IllegalStateException("conflict detection service is not wired");
            }
            ConflictDetectionService.Outcome outcome = conflictDetectionService.detect(review);
            if (outcome.result().candidates().isEmpty()) {
                return ToolResultBlock.text("candidates=[]; gateRisks=" + outcome.gateRiskAssessments().size()
                        + "; use skip_debate_when_no_conflicts when no candidate remains");
            }
            String payload = outcome.result().candidates().stream()
                    .map(candidate -> "subjectKey=" + sanitize(candidate.subjectKey())
                            + "; claimIds=[" + candidate.claimIds().stream()
                                    .map(claimId -> claimId.value().toString())
                                    .collect(java.util.stream.Collectors.joining(", "))
                            + "]; explanation=" + sanitize(candidate.explanation()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            return ToolResultBlock.text(payload + "\ngateRisks=" + outcome.gateRiskAssessments().size()
                    + "; register every candidate with register_topics or justify a skip");
        }

        private String sanitize(String value) {
            return value.replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Director batch-submits all chosen topic candidates in one command;
     * the server validates everything first, then atomically registers and advances the stage once.
     *
     * @author wangli
     */
    private final class RegisterTopicsTool extends BoundTool {
        private RegisterTopicsTool(ReviewRuntimeContext context) { super(context, RoleType.DIRECTOR); }
        @Override public String getName() { return "register_topics"; }
        @Override public String getDescription() { return "Register ALL chosen conflict topics in one batch from list_conflict_candidates subjects; the server validates and deduplicates every proposal first, then atomically saves all topics and moves the stage to debate round one exactly once. claimIds may be empty for assessment-only contradiction subjects."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of(
                "topics", Map.of("type", "array", "description", "Every candidate subject to register",
                        "items", objectSchema(Map.of(
                                "subjectKey", stringSchema("Conflict candidate subject key"),
                                "claimIds", idArraySchema("Persisted Claim UUIDs of the subject")), List.of("subjectKey")))),
                List.of("topics")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            Object value = input.get("topics");
            if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
                throw new IllegalArgumentException("topics must be a non-empty array");
            }
            List<DebateToolCommands.TopicProposal> proposals = collection.stream()
                    .map(element -> {
                        if (!(element instanceof Map<?, ?> entry)) {
                            throw new IllegalArgumentException("each topic must be an object");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> topic = (Map<String, Object>) entry;
                        Object subject = topic.get("subjectKey");
                        if (subject == null || subject.toString().isBlank()) {
                            throw new IllegalArgumentException("subjectKey is required");
                        }
                        return new DebateToolCommands.TopicProposal(subject.toString(), claimIds(topic.get("claimIds")));
                    })
                    .toList();
            DebateService.RegisterTopicsResult result = debateTools.registerDebateTopics(review,
                    new DebateToolCommands.RegisterTopics(metadata, RoleType.DIRECTOR, proposals));
            String payload = result.topics().stream()
                    .map(topicResult -> "topicId=" + topicResult.topic().id().value())
                    .collect(java.util.stream.Collectors.joining("; "));
            return ToolResultBlock.text(payload + "; replayed=" + result.replayed());
        }
    }

    /** Exposes only persisted public debate identifiers and facts needed for a bounded turn. */
    private final class ListPersistedDebateTopicsTool extends BoundTool {

        private ListPersistedDebateTopicsTool(ReviewRuntimeContext context, RoleType actorRole) {
            super(context, actorRole);
        }

        @Override
        public String getName() {
            return "list_persisted_debate_topics";
        }

        @Override
        public String getDescription() {
            return "List authoritative persisted debate topics, Claim IDs, and turn IDs before a debate turn or Judge decision.";
        }

        @Override
        public Map<String, Object> getParameters() {
            return objectSchema(Map.of(), List.of());
        }

        @Override
        ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            requireNoInput(input);
            List<ai.cc.chongming.review.domain.model.DebateTopic> topics = debateStore.findTopics(review.id());
            if (topics.isEmpty()) {
                return ToolResultBlock.text("topics=[]");
            }
            Map<ClaimId, Claim> claims = debateStore.findClaims(review.id()).stream()
                    .collect(java.util.stream.Collectors.toMap(Claim::claimId, claim -> claim));
            Map<TopicId, List<DebateTurn>> turnsByTopic = debateStore.findTurns(review.id()).stream()
                    .collect(java.util.stream.Collectors.groupingBy(DebateTurn::topicId));
            String payload = topics.stream()
                    .sorted(java.util.Comparator.comparing(topic -> topic.id().value()))
                    .map(topic -> "topicId=" + topic.id().value()
                            + "; subjectKey=" + sanitize(topic.subjectKey())
                            + "; status=" + topic.status()
                            + "; round=" + topic.currentRound()
                            + "; claims=[" + topic.claimIds().stream()
                                    .map(claimId -> describeClaim(claims.get(claimId)))
                                    .collect(java.util.stream.Collectors.joining(", "))
                            + "]; turns=[" + turnsByTopic.getOrDefault(topic.id(), List.of()).stream()
                                    .sorted(java.util.Comparator.comparing(turn -> turn.turnId().value()))
                                    .map(turn -> "turnId=" + turn.turnId().value()
                                            + ":round=" + turn.round()
                                            + ":actor=" + turn.actorRole()
                                            + ":targetRole=" + turn.targetRole()
                                            + ":type=" + turn.turnType())
                                    .collect(java.util.stream.Collectors.joining(", "))
                            + "]")
                    .collect(java.util.stream.Collectors.joining("\n"));
            return ToolResultBlock.text(payload);
        }

        private String describeClaim(Claim claim) {
            if (claim == null) {
                return "missing";
            }
            return "claimId=" + claim.claimId().value()
                    + ":role=" + claim.roleType()
                    + ":position=" + claim.position()
                    + ":severity=" + claim.severity()
                    + ":statement=" + sanitize(claim.statement());
        }

        private String sanitize(String value) {
            return value.replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * [AIREVIEW-PLAN-024#方案3] Director issues directed dispatch envelopes instead of relying on
     * broadcast prompts; the server validates and persists the command before any delivery.
     *
     * @author wangli
     */
    private final class DispatchDebateActionTool extends BoundTool {
        private DispatchDebateActionTool(ReviewRuntimeContext context) { super(context, RoleType.DIRECTOR); }
        @Override public String getName() { return "dispatch_debate_action"; }
        @Override public String getDescription() { return "Issue one directed dispatch command authorizing exactly one write action "
                + "(CHALLENGE, REBUTTAL, POSITION_CHANGE, EVIDENCE_REQUEST or DEFENSE) for one recipient role on one topic. "
                + "DEFENSE authorizes the requirement defender to submit a SUPPORT claim on the topic's subjectKey. "
                + "The server validates the recipient, targets, topic status and round, then delivers the envelope only to the recipient; "
                + "never instruct roles with free text instead."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of(
                "recipientRole", enumSchema(RoleType.PRODUCT.name(), RoleType.PROJECT.name(), RoleType.FRONTEND.name(), RoleType.BACKEND.name()),
                "allowedAction", enumSchema("CHALLENGE", "REBUTTAL", "POSITION_CHANGE", "EVIDENCE_REQUEST", "DEFENSE"),
                "topicId", stringSchema("Debate topic UUID"),
                "targetClaimId", stringSchema("Target Claim UUID; required for CHALLENGE, POSITION_CHANGE and EVIDENCE_REQUEST"),
                "targetTurnId", stringSchema("Target Turn UUID; required for REBUTTAL"),
                "expiresInSeconds", Map.of("type", "integer", "description", "Optional envelope lifetime in seconds, clamped to 60..3600")),
                List.of("recipientRole", "allowedAction", "topicId")); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            if (dispatchService == null) {
                throw new IllegalStateException("dispatch service is not wired");
            }
            long ttlSeconds = input.get("expiresInSeconds") instanceof Number number
                    ? number.longValue() : DISPATCH_DEFAULT_TTL_SECONDS;
            ttlSeconds = Math.max(DISPATCH_MIN_TTL_SECONDS, Math.min(DISPATCH_MAX_TTL_SECONDS, ttlSeconds));
            int round = review.stage() == ReviewStage.DEBATE_ROUND_2 ? 2 : 1;
            UUID targetClaimUuid = optionalUuid(input, "targetClaimId");
            UUID targetTurnUuid = optionalUuid(input, "targetTurnId");
            ReviewDispatchService.DispatchProposal proposal = new ReviewDispatchService.DispatchProposal(
                    metadata,
                    role(input, "recipientRole"),
                    DispatchedAction.valueOf(text(input, "allowedAction")),
                    round,
                    topicId(input),
                    targetClaimUuid == null ? null : new ClaimId(targetClaimUuid),
                    targetTurnUuid == null ? null : new TurnId(targetTurnUuid),
                    Instant.now().plus(Duration.ofSeconds(ttlSeconds)),
                    RoleType.DIRECTOR,
                    "DIRECTOR");
            ReviewDispatchService.DispatchIssueResult result = dispatchService.issue(review, proposal);
            return ToolResultBlock.text("commandId=" + result.command().commandId().value()
                    + "; status=" + result.command().status() + "; replayed=" + result.replayed());
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
            // [AIREVIEW-PLAN-024#方案3] The broadcast round-two prompt is removed; the committed
            // DEBATE_ROUND_2_STARTED event wakes the Director to issue directed dispatch commands.
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
            boolean replayed = advanceStage(review, metadata, List.of(ReviewStage.DEBATE_ROUND_2, ReviewStage.DEBATE_ROUND_1),
                    "begin-judging", () -> debateService.beginJudging(review));
            // The Judge wake is owned by ReviewWorkflowDispatcher's JUDGING_STARTED handler so every
            // path into judging (Director tool, forced convergence) dispatches exactly once.
            return ToolResultBlock.text("stage=" + review.stage() + "; replayed=" + replayed);
        }
    }

    private final class SkipDebateWhenNoConflictsTool extends BoundTool {
        private SkipDebateWhenNoConflictsTool(ReviewRuntimeContext context) { super(context, RoleType.DIRECTOR); }
        @Override public String getName() { return "skip_debate_when_no_conflicts"; }
        @Override public String getDescription() { return "Enter judging only when persisted Claims have no conflicting positions and no debate topic exists."; }
        @Override public Map<String, Object> getParameters() { return objectSchema(Map.of(), List.of()); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            requireNoInput(input);
            boolean replayed = advanceStage(review, metadata, ReviewStage.CONFLICT_DETECTION,
                    "skip-debate-no-conflicts", () -> debateService.skipDebateWhenNoConflicts(review));
            // The Judge wake is owned by ReviewWorkflowDispatcher's DEBATE_SKIPPED handler.
            return ToolResultBlock.text("stage=" + review.stage() + "; replayed=" + replayed);
        }
    }

    private final class ChallengeTool extends BoundTool {
        private ChallengeTool(ReviewRuntimeContext context, RoleType actor) { super(context, actor); }
        @Override public String getName() { return "submit_challenge"; }
        @Override public String getDescription() { return "Submit a directed public challenge against an existing Claim. Requires the commandId of a valid dispatch envelope addressed to this role."; }
        @Override public Map<String, Object> getParameters() { return turnSchema(merge(Map.of("targetClaimId", stringSchema("Target Claim UUID"),
                "evidenceGap", stringSchema("Required when no evidenceIds are supplied")), commandIdSchema()), writeRequired(List.of("targetRole", "topicId", "round", "targetClaimId", "publicContent"))); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            ReviewDispatchCommand command = resolveCommand(review, input, DispatchedAction.CHALLENGE);
            DebateService.TurnResult result = debateTools.submitChallenge(review, new DebateToolCommands.Challenge(metadata, actor(), role(input, "targetRole"),
                    topicId(input), integer(input, "round"), claimId(input, "targetClaimId"), text(input, "publicContent"), evidenceIds(input.get("evidenceIds")), optionalText(input, "evidenceGap")));
            consumeCommand(review, command);
            return ToolResultBlock.text("turnId=" + result.turn().turnId().value() + "; replayed=" + result.replayed());
        }
    }

    private final class RebuttalTool extends BoundTool {
        private RebuttalTool(ReviewRuntimeContext context, RoleType actor) { super(context, actor); }
        @Override public String getName() { return "submit_rebuttal"; }
        @Override public String getDescription() { return "Submit a directed public rebuttal to an existing debate turn. Requires the commandId of a valid dispatch envelope addressed to this role."; }
        @Override public Map<String, Object> getParameters() { return turnSchema(merge(Map.of("targetTurnId", stringSchema("Target Turn UUID")), commandIdSchema()),
                writeRequired(List.of("targetRole", "topicId", "round", "targetTurnId", "publicContent"))); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            ReviewDispatchCommand command = resolveCommand(review, input, DispatchedAction.REBUTTAL);
            DebateService.TurnResult result = debateTools.submitRebuttal(review, new DebateToolCommands.Rebuttal(metadata, actor(), role(input, "targetRole"),
                    topicId(input), integer(input, "round"), new TurnId(uuid(input, "targetTurnId")), text(input, "publicContent"), evidenceIds(input.get("evidenceIds"))));
            consumeCommand(review, command);
            return ToolResultBlock.text("turnId=" + result.turn().turnId().value() + "; replayed=" + result.replayed());
        }
    }

    private final class PositionChangeTool extends BoundTool {
        private PositionChangeTool(ReviewRuntimeContext context, RoleType actor) { super(context, actor); }
        @Override public String getName() { return "change_claim_position"; }
        @Override public String getDescription() { return "Record a non-destructive position change for a Claim owned by this role. Requires the commandId of a valid dispatch envelope addressed to this role."; }
        @Override public Map<String, Object> getParameters() { return turnSchema(merge(Map.of("targetClaimId", stringSchema("Owned Claim UUID"),
                "stanceAfter", enumSchema("SUPPORT", "OPPOSE", "NEUTRAL")), commandIdSchema()), writeRequired(List.of("topicId", "round", "targetClaimId", "stanceAfter", "publicContent"))); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            ReviewDispatchCommand command = resolveCommand(review, input, DispatchedAction.POSITION_CHANGE);
            DebateService.TurnResult result = debateTools.changePosition(review, new DebateToolCommands.PositionChange(metadata, actor(), topicId(input), integer(input, "round"),
                    claimId(input, "targetClaimId"), ClaimPosition.valueOf(text(input, "stanceAfter")), text(input, "publicContent"), evidenceIds(input.get("evidenceIds"))));
            consumeCommand(review, command);
            return ToolResultBlock.text("turnId=" + result.turn().turnId().value() + "; replayed=" + result.replayed());
        }
    }

    private final class EvidenceRequestTool extends BoundTool {
        private EvidenceRequestTool(ReviewRuntimeContext context, RoleType actor) { super(context, actor); }
        @Override public String getName() { return "request_additional_evidence"; }
        @Override public String getDescription() { return "Request missing evidence from the role that owns a Claim; this does not fabricate evidence. Requires the commandId of a valid dispatch envelope addressed to this role."; }
        @Override public Map<String, Object> getParameters() { return turnSchema(merge(Map.of("targetClaimId", stringSchema("Target Claim UUID")), commandIdSchema()),
                writeRequired(List.of("targetRole", "topicId", "round", "targetClaimId", "publicContent"))); }
        @Override ToolResultBlock invoke(Review review, ReviewCommandMetadata metadata, Map<String, Object> input) {
            ReviewDispatchCommand command = resolveCommand(review, input, DispatchedAction.EVIDENCE_REQUEST);
            DebateService.TurnResult result = debateTools.requestAdditionalEvidence(review, new DebateToolCommands.EvidenceRequest(metadata, actor(), role(input, "targetRole"),
                    topicId(input), integer(input, "round"), claimId(input, "targetClaimId"), text(input, "publicContent")));
            consumeCommand(review, command);
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
        return advanceStage(review, metadata, List.of(expectedStage), reference, operation);
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Stage transitions may permit several source stages (early
     * convergence from DEBATE_ROUND_1 straight to JUDGING).
     */
    private boolean advanceStage(
            Review review, ReviewCommandMetadata metadata, List<ReviewStage> expectedStages, String reference, Runnable operation) {
        if (!review.id().equals(metadata.reviewId())) throw new IllegalArgumentException("review identity does not match tool runtime");
        if (review.commandResults().containsKey(metadata.idempotencyKey())) return true;
        if (!expectedStages.contains(review.stage()) || review.version() != metadata.expectedVersion()) {
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
    private static UUID optionalUuid(Map<String, Object> input, String name) { Object value = input.get(name); if (value == null || value.toString().isBlank()) return null; return UUID.fromString(value.toString()); }
    private static Map<String, Object> merge(Map<String, Object> first, Map<String, Object> second) { java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(first); merged.putAll(second); return Map.copyOf(merged); }
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
