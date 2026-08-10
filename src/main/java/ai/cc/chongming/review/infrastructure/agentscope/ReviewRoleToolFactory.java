package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ClaimService;
import ai.cc.chongming.review.application.InitialReviewProgressService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.model.Review;
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
 * [AIREVIEW-PLAN-009#1.4] Creates review-bound write tools. The model supplies only public
 * fields; review identity, actor identity, version, and idempotency are server controlled.
 *
 * @author wangli
 */
@Component
public class ReviewRoleToolFactory {

    private final ReviewRegistry reviewRegistry;
    private final ClaimService claimService;
    private final InitialReviewProgressService progressService;

    public ReviewRoleToolFactory(
            ReviewRegistry reviewRegistry,
            ClaimService claimService,
            InitialReviewProgressService progressService) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.claimService = Objects.requireNonNull(claimService, "claimService must not be null");
        this.progressService = Objects.requireNonNull(progressService, "progressService must not be null");
    }

    public List<AgentTool> initialReviewTools(ReviewRuntimeContext context, RoleType roleType) {
        Objects.requireNonNull(context, "runtimeContext must not be null");
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE) {
            throw new IllegalArgumentException("only review roles may receive initial-review tools");
        }
        return List.of(new SubmitClaimTool(context, roleType), new CompleteInitialReviewTool(context, roleType));
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
            return "Submit one public, auditable review claim. Call once for every finding, then call complete_initial_review.";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "subjectKey", stringSchema("Stable public subject key"),
                            "severity", enumSchema("P0", "P1", "P2", "P3"),
                            "position", enumSchema("SUPPORT", "OPPOSE", "NEUTRAL"),
                            "statement", stringSchema("Public claim statement"),
                            "reasonSummary", stringSchema("Public evidence-based rationale"),
                            "evidenceIds", Map.of("type", "array", "items", Map.of("type", "string"))),
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
                    ClaimService.ClaimSubmission submission = new ClaimService.ClaimSubmission(
                            metadata(review, roleType, param), roleType,
                            requiredText(input, "subjectKey"), ClaimSeverity.valueOf(requiredText(input, "severity")),
                            ClaimPosition.valueOf(requiredText(input, "position")), requiredText(input, "statement"),
                            requiredText(input, "reasonSummary"), evidenceIds(input.get("evidenceIds")));
                    ClaimService.ClaimSubmissionResult result = claimService.submit(review, submission);
                    return ToolResultBlock.text("claimId=" + result.claim().claimId().value() + "; replayed=" + result.replayed());
                }
            }).onErrorResume(exception -> Mono.just(ToolResultBlock.error("claim submission rejected")));
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
            return "Explicitly finish this role's first review after all claims have been submitted, including when there are no findings.";
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
                    InitialReviewProgressService.CompletionResult result = progressService.completeWithoutClaim(
                            review, metadata(review, roleType, param), roleType,
                            requiredText(param.getInput(), "publicSummary"));
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

    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> enumSchema(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
