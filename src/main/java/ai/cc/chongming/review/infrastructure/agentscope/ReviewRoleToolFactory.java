package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.AssessmentService;
import ai.cc.chongming.review.application.ClaimService;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.ReviewDispatchService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.CommandId;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
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
 * [AIREVIEW-PLAN-009#1.4][AIREVIEW-PLAN-024#方案1] Creates review-bound write tools. The model
 * supplies only public fields; review identity, actor identity, version, and idempotency are
 * server controlled.
 *
 * @author wangli
 */
@Component
public class ReviewRoleToolFactory {

    private final ReviewRegistry reviewRegistry;
    private final ClaimService claimService;
    private final InitialReviewProgressService progressService;
    private final AssessmentService assessmentService;
    private final ReviewDebateStore debateStore;
    private final ReviewDispatchService dispatchService;

    public ReviewRoleToolFactory(
            ReviewRegistry reviewRegistry,
            ClaimService claimService,
            InitialReviewProgressService progressService,
            AssessmentService assessmentService,
            ReviewDebateStore debateStore) {
        this(reviewRegistry, claimService, progressService, assessmentService, debateStore, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewRoleToolFactory(
            ReviewRegistry reviewRegistry,
            ClaimService claimService,
            InitialReviewProgressService progressService,
            AssessmentService assessmentService,
            ReviewDebateStore debateStore,
            ReviewDispatchService dispatchService) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.claimService = Objects.requireNonNull(claimService, "claimService must not be null");
        this.progressService = Objects.requireNonNull(progressService, "progressService must not be null");
        this.assessmentService = Objects.requireNonNull(assessmentService, "assessmentService must not be null");
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.dispatchService = dispatchService;
    }

    public List<AgentTool> initialReviewTools(ReviewRuntimeContext context, RoleType roleType) {
        Objects.requireNonNull(context, "runtimeContext must not be null");
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE) {
            throw new IllegalArgumentException("only review roles may receive initial-review tools");
        }
        return List.of(
                new SubmitAssessmentTool(context, roleType),
                new SubmitClaimTool(context, roleType),
                new CompleteInitialReviewTool(context, roleType));
    }

    private final class SubmitAssessmentTool implements AgentTool {
        private final ReviewRuntimeContext context;
        private final RoleType roleType;

        private SubmitAssessmentTool(ReviewRuntimeContext context, RoleType roleType) {
            this.context = context;
            this.roleType = roleType;
        }

        @Override
        public String getName() {
            return "submit_assessment";
        }

        @Override
        public String getDescription() {
            return "Submit one structured checkpoint assessment. Every checkpoint of your checklist needs exactly one conclusion: "
                    + "CONFIRMED when authorized evidence is sufficient, PARTIAL when only partly satisfied, GAP when a confirmed gap exists, "
                    + "UNKNOWN when the required evidence is outside your authorized scope, NOT_APPLICABLE when the checkpoint does not apply. "
                    + "UNKNOWN must name the missing authorized evidence in reasonSummary; never write 'file not read' as 'feature does not exist'. "
                    + "Role identity, review, attempt, version and idempotency are injected by the server.";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "checkpointKey", stringSchema("Stable checkpoint key from this role's checklist"),
                            "status", enumSchema("CONFIRMED", "PARTIAL", "GAP", "UNKNOWN", "NOT_APPLICABLE"),
                            "summary", stringSchema("Public checkpoint conclusion"),
                            "reasonSummary", stringSchema("Required for PARTIAL, GAP and UNKNOWN: the unmet part, the gap, or the missing authorized evidence"),
                            "evidenceIds", Map.of("type", "array", "items", Map.of("type", "string"))),
                    "required", List.of("checkpointKey", "status", "summary"),
                    "additionalProperties", false);
        }

        @Override
        public Boolean getStrict() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromSupplier(() -> {
                Review review = requireReview(context);
                synchronized (review) {
                    Map<String, Object> input = param.getInput();
                    AssessmentService.AssessmentSubmission submission = new AssessmentService.AssessmentSubmission(
                            metadata(review, roleType, param), roleType,
                            requiredText(input, "checkpointKey"),
                            AssessmentStatus.valueOf(requiredText(input, "status")),
                            requiredText(input, "summary"),
                            optionalText(input, "reasonSummary"),
                            evidenceIds(input.get("evidenceIds")));
                    AssessmentService.AssessmentSubmissionResult result = assessmentService.submit(review, submission);
                    return ToolResultBlock.text("assessmentSaved=true; checkpointKey="
                            + result.assessment().checkpointKey() + "; status=" + result.assessment().status()
                            + "; replayed=" + result.replayed());
                }
            }).onErrorResume(exception -> Mono.just(ToolResultBlock.error(
                    "assessmentSubmissionRejected: " + rejectionReason(exception))));
        }
    }

    private final class SubmitClaimTool implements AgentTool {
        private final ReviewRuntimeContext context;
        private final RoleType roleType;

        private SubmitClaimTool(ReviewRuntimeContext context, RoleType roleType) {
            this.context = context;
            this.roleType = roleType;
        }

        @Override
        public String getName() {
            return "submit_claim";
        }

        @Override
        public String getDescription() {
            return "Submit one public, auditable review claim. Call only when you confirm a risk gap or form a debatable proposition; "
                    + "positive checkpoint conclusions belong to submit_assessment instead.";
        }

        @Override
        public Map<String, Object> getParameters() {
            java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>();
            properties.put("subjectKey", stringSchema("Stable public subject key"));
            properties.put("severity", enumSchema("P0", "P1", "P2", "P3"));
            properties.put("position", enumSchema("SUPPORT", "OPPOSE", "NEUTRAL"));
            properties.put("statement", stringSchema("Public claim statement"));
            properties.put("reasonSummary", stringSchema("Public evidence-based rationale"));
            properties.put("evidenceIds", Map.of("type", "array", "items", Map.of("type", "string")));
            if (dispatchService != null) {
                properties.put("commandId", stringSchema(
                        "Dispatch command UUID authorizing this claim submission; required during debate rounds "
                                + "when the command is a DEFENSE envelope addressed to this role"));
            }
            return Map.of(
                    "type", "object",
                    "properties", Map.copyOf(properties),
                    "required", List.of("subjectKey", "severity", "position", "statement", "reasonSummary"),
                    "additionalProperties", false);
        }

        @Override
        public Boolean getStrict() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromSupplier(() -> {
                Review review = requireReview(context);
                synchronized (review) {
                    Map<String, Object> input = param.getInput();
                    // [DEFENSE] During debate rounds the claim must reference a valid DEFENSE
                    // dispatch command; the server re-validates it inside ClaimService (PENDING,
                    // unexpired, recipient, action, round, subjectKey), so this resolution only
                    // fails fast and yields the command to consume after the claim commits.
                    ReviewDispatchCommand defenseCommand =
                            resolveDefenseCommand(review, roleType, input);
                    ClaimService.ClaimSubmission submission = new ClaimService.ClaimSubmission(
                            metadata(review, roleType, param), roleType,
                            requiredText(input, "subjectKey"), ClaimSeverity.valueOf(requiredText(input, "severity")),
                            ClaimPosition.valueOf(requiredText(input, "position")), requiredText(input, "statement"),
                            requiredText(input, "reasonSummary"), evidenceIds(input.get("evidenceIds")),
                            defenseCommand == null ? null : defenseCommand.commandId());
                    ClaimService.ClaimSubmissionResult result = claimService.submit(review, submission);
                    consumeDefenseCommand(review, defenseCommand);
                    return ToolResultBlock.text("claimId=" + result.claim().claimId().value() + "; replayed=" + result.replayed());
                }
            }).onErrorResume(exception -> Mono.just(ToolResultBlock.error(
                    "claim submission rejected: " + rejectionReason(exception))));
        }
    }

    /**
     * [DEFENSE] Resolves the DEFENSE dispatch command referenced by a debate-round
     * {@code submit_claim}. Returns null when no dispatch service is wired (initial-review-only
     * legacy path) or when the review is still in INITIAL_REVIEW and no commandId was supplied.
     */
    private ReviewDispatchCommand resolveDefenseCommand(
            Review review, RoleType actorRole, Map<String, Object> input) {
        if (dispatchService == null) {
            return null;
        }
        Object value = input.get("commandId");
        if (review.stage() == ReviewStage.INITIAL_REVIEW) {
            return null;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return dispatchService.resolveForWrite(
                review, actorRole, new CommandId(UUID.fromString(value.toString())), DispatchedAction.DEFENSE);
    }

    private void consumeDefenseCommand(Review review, ReviewDispatchCommand command) {
        if (dispatchService != null && command != null) {
            dispatchService.consume(review, command);
        }
    }

    private final class CompleteInitialReviewTool implements AgentTool {
        private final ReviewRuntimeContext context;
        private final RoleType roleType;

        private CompleteInitialReviewTool(ReviewRuntimeContext context, RoleType roleType) {
            this.context = context;
            this.roleType = roleType;
        }

        @Override
        public String getName() {
            return "complete_initial_review";
        }

        @Override
        public String getDescription() {
            return "Explicitly finish this role's first review after every checkpoint assessment has been submitted via submit_assessment. "
                    + "The server rejects completion while required checkpoints are missing; publicSummary is only supplemental because the "
                    + "public summary is derived server-side from persisted assessments and claims.";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of(
                    "publicSummary", stringSchema("Public completion summary")),
                    "required", List.of("publicSummary"), "additionalProperties", false);
        }

        @Override
        public Boolean getStrict() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromSupplier(() -> {
                Review review = requireReview(context);
                synchronized (review) {
                    // [AIREVIEW-PLAN-024#方案1] The public completion summary is assembled from
                    // persisted assessments and claims; the model text is supplemental only.
                    List<Claim> roleClaims = debateStore.findClaims(review.id()).stream()
                            .filter(claim -> claim.roleType() == roleType)
                            .toList();
                    String derivedSummary = assessmentService.derivedCompletionSummary(
                            review.id(), review.attemptNo(), roleType, roleClaims,
                            requiredText(param.getInput(), "publicSummary"));
                    InitialReviewProgressService.CompletionResult result = progressService.completeWithoutClaim(
                            review, metadata(review, roleType, param), roleType, derivedSummary);
                    return ToolResultBlock.text("initialReviewCompleted=true; stage=" + result.stage() + "; replayed=" + result.replayed());
                }
            }).onErrorResume(exception -> Mono.just(ToolResultBlock.error(
                    "initialReviewCompletionRejected: " + rejectionReason(exception))));
        }

        private String rejectionReason(Throwable failure) {
            if (failure instanceof ai.cc.chongming.review.domain.exception.ReviewDomainException domain) {
                return domain.errorCode().name() + ": " + domain.getMessage();
            }
            return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        }
    }

    private Review requireReview(ReviewRuntimeContext context) {
        return reviewRegistry.find(context.reviewId())
                .filter(review -> review.attemptNo() == context.attemptNo())
                .orElseThrow(() -> new IllegalStateException("active review was not found"));
    }

    private ReviewCommandMetadata metadata(Review review, RoleType roleType, ToolCallParam param) {
        String callId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("tool call id is required");
        }
        return new ReviewCommandMetadata(review.id(), review.version(),
                new IdempotencyKey("tool:" + review.id().value() + ":" + roleType.name() + ":" + callId));
    }

    private List<EvidenceId> evidenceIds(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Collection<?> values)) {
            throw new IllegalArgumentException("evidenceIds must be an array");
        }
        return values.stream().map(Object::toString).map(UUID::fromString).map(EvidenceId::new).toList();
    }

    private static String requiredText(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    private static String optionalText(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    private static String rejectionReason(Throwable failure) {
        if (failure instanceof ai.cc.chongming.review.domain.exception.ReviewDomainException domain) {
            return domain.errorCode().name() + ": " + domain.getMessage();
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> enumSchema(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
