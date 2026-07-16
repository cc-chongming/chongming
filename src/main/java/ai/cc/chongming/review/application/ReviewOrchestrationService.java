package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.ReviewOrchestrationProperties;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewPlan;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeRoleRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeSession;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeStartRequest;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * [AIREVIEW-PLAN-010#1.5,#1.6,#1.7] Coordinates director planning and role activation while delegating all lifecycle limits to domain guards.
 *
 * @author wangli
 */
@Service
public class ReviewOrchestrationService {

    private static final List<RoleType> CORE_ROLES = List.of(
            RoleType.PRODUCT, RoleType.PROJECT, RoleType.FRONTEND, RoleType.BACKEND);

    private final AgentRuntimeAdapter runtimeAdapter;
    private final ReviewWorkspaceLayout workspaceLayout;
    private final RoleActivationService roleActivationService;
    private final ReviewStateMachine stateMachine;
    private final ReviewOrchestrationProperties properties;
    private final ReviewEventPublisher eventPublisher;
    private final ConcurrentMap<String, List<ReviewPlan>> plansByRuntime = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<OrchestrationEvent>> eventsByRuntime = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> eventSequences = new ConcurrentHashMap<>();

    public ReviewOrchestrationService(
            AgentRuntimeAdapter runtimeAdapter,
            ReviewWorkspaceLayout workspaceLayout,
            RoleActivationService roleActivationService,
            ReviewStateMachine stateMachine,
            ReviewOrchestrationProperties properties) {
        this(runtimeAdapter, workspaceLayout, roleActivationService, stateMachine, properties, ReviewEventPublisher.noop());
    }

    @Autowired
    public ReviewOrchestrationService(
            AgentRuntimeAdapter runtimeAdapter,
            ReviewWorkspaceLayout workspaceLayout,
            RoleActivationService roleActivationService,
            ReviewStateMachine stateMachine,
            ReviewOrchestrationProperties properties,
            ReviewEventPublisher eventPublisher) {
        this.runtimeAdapter = Objects.requireNonNull(runtimeAdapter, "runtimeAdapter must not be null");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
        this.roleActivationService = Objects.requireNonNull(roleActivationService, "roleActivationService must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /**
     * Starts an already snapshotted review from PLANNING, persists the public total plan and launches all core roles.
     */
    public Mono<StartResult> start(StartRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Review review = request.review();
        ReviewRuntimeContext context = request.runtimeContext();
        requirePlanningReview(review, context);
        ReviewWorkspaceLayout.ReviewWorkspace workspace = workspaceLayout.open(context);
        ReviewPlan initialPlan = appendPlan(context, request.publicTasks(), request.changeReason(), "DIRECTOR");
        writePlan(workspace, initialPlan, context);
        emit(context, OrchestrationEventType.PLAN_CREATED, ReviewStage.PLANNING, "DIRECTOR", initialPlan.planVersion());

        AgentRuntimeStartRequest runtimeRequest = new AgentRuntimeStartRequest(
                context.runtimeId(), context.userId(), context.directorSessionId(), request.initialMessage(), context);
        return runtimeAdapter.start(runtimeRequest).flatMap(session -> {
            review.transitionTo(stateMachine, ReviewStage.INITIAL_REVIEW);
            return Flux.fromIterable(CORE_ROLES)
                    .concatMap(roleType -> activateRole(
                            review,
                            context,
                            new RoleActivationService.ActivationRequest(
                                    roleType,
                                    RoleActivationService.ActivationSource.PLAN,
                                    "Core first-round review required by protocol",
                                    List.of())))
                    .collectList()
                    .map(activations -> new StartResult(session, initialPlan, activations));
        });
    }

    /**
     * Requests one additional role through Guard validation and runtime creation before mutating the aggregate.
     */
    public Mono<RoleActivationService.ActivationReceipt> activateRole(
            Review review, ReviewRuntimeContext context, RoleActivationService.ActivationRequest request) {
        RoleActivationService.ActivationReceipt receipt = roleActivationService.approve(review, context, request);
        AgentRuntimeRoleRequest runtimeRequest = new AgentRuntimeRoleRequest(
                context.runtimeId(),
                context,
                receipt.activation().roleType(),
                receipt.activation().agentLabel(),
                context.roleSessionId(receipt.activation().roleType()));
        return runtimeAdapter.registerRole(runtimeRequest)
                .then(Mono.fromSupplier(() -> {
                    roleActivationService.apply(review, receipt);
                    emit(context, OrchestrationEventType.ROLE_ACTIVATED, review.stage(),
                            receipt.activation().roleType().name(), review.version());
                    return receipt;
                }))
                .flatMap(approved -> runtimeAdapter.send(
                        context.runtimeId(),
                        approved.activation().agentLabel(),
                        "Perform the assigned review role. Activation reason: " + approved.reason())
                        .thenReturn(approved));
    }

    /**
     * Records a bounded stage-plan revision and emits an application-level revision event.
     */
    public PlanRevision revisePlan(
            ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace, List<String> publicTasks, String reason) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        List<ReviewPlan> history = planHistory(context.runtimeId());
        ReviewPlan previous;
        synchronized (history) {
            if (history.isEmpty()) {
                throw new IllegalStateException("a total plan must exist before a revision");
            }
            if (history.size() >= properties.maxPlanRevisions()) {
                throw new IllegalStateException("maximum plan revision count has been reached");
            }
            previous = history.getLast();
        }
        ReviewPlan revised = appendPlan(context, publicTasks, reason, "DIRECTOR");
        writePlan(workspace, revised, context);
        emit(context, OrchestrationEventType.PLAN_REVISED, ReviewStage.PLANNING, "DIRECTOR", revised.planVersion());
        return new PlanRevision(previous.planVersion(), revised, reason);
    }

    /**
     * Propagates cancellation to every active agent before performing the permitted terminal transition.
     */
    public Mono<Void> cancel(Review review, ReviewRuntimeContext context) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!review.id().equals(context.reviewId()) || review.attemptNo() != context.attemptNo()) {
            return Mono.error(new IllegalArgumentException("runtime context must identify the active review attempt"));
        }
        review.transitionTo(stateMachine, ReviewStage.CANCELLING);
        return runtimeAdapter.cancel(context.runtimeId()).doOnSuccess(ignored -> {
            review.transitionTo(stateMachine, ReviewStage.CANCELLED);
            emit(context, OrchestrationEventType.CANCELLED, ReviewStage.CANCELLED, "DIRECTOR", review.version());
        });
    }

    /**
     * Requests a best-effort runtime safe point without changing aggregate state.
     * Lifecycle commands own the subsequent version-checked terminal transition.
     */
    public Mono<Void> requestRuntimeCancellation(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        return runtimeAdapter.cancel(ReviewRuntimeContext.runtimeIdFor(reviewId, attemptNo));
    }

    /**
     * Returns a defensive copy of ephemeral orchestration observations; durable events are added in PLAN-010.
     */
    public List<OrchestrationEvent> events(ReviewRuntimeContext context) {
        return List.copyOf(eventsByRuntime.getOrDefault(context.runtimeId(), List.of()));
    }

    /**
     * Returns the public total/stage plans currently retained for this process.
     */
    public List<ReviewPlan> plans(ReviewRuntimeContext context) {
        return List.copyOf(planHistory(context.runtimeId()));
    }

    private ReviewPlan appendPlan(
            ReviewRuntimeContext context, List<String> publicTasks, String changeReason, String changedBy) {
        List<String> tasks = validateTasks(publicTasks);
        List<ReviewPlan> history = planHistory(context.runtimeId());
        synchronized (history) {
            if (history.size() >= properties.maxPlanRevisions()) {
                throw new IllegalStateException("maximum plan revision count has been reached");
            }
            ReviewPlan plan = new ReviewPlan(
                    context.reviewId(), history.size() + 1, tasks, requireText(changeReason, "changeReason"),
                    requireText(changedBy, "changedBy"), Instant.now());
            history.add(plan);
            return plan;
        }
    }

    private void writePlan(
            ReviewWorkspaceLayout.ReviewWorkspace workspace, ReviewPlan plan, ReviewRuntimeContext context) {
        String payload = "planVersion: " + plan.planVersion() + "\nchangeReason: " + plan.changeReason()
                + "\nchangedBy: " + plan.changedBy() + "\ntasks:\n- " + String.join("\n- ", plan.publicTasks());
        workspaceLayout.writeArtifact(
                workspace,
                ReviewWorkspaceLayout.ArtifactArea.PLANS,
                "plan-v" + plan.planVersion() + ".json",
                payload,
                context);
    }

    private List<ReviewPlan> planHistory(String runtimeId) {
        return plansByRuntime.computeIfAbsent(runtimeId, ignored -> new ArrayList<>());
    }

    private void emit(
            ReviewRuntimeContext context,
            OrchestrationEventType type,
            ReviewStage stage,
            String actor,
            long referenceVersion) {
        List<OrchestrationEvent> events = eventsByRuntime.computeIfAbsent(
                context.runtimeId(), ignored -> java.util.Collections.synchronizedList(new ArrayList<>()));
        long sequence = eventSequences.computeIfAbsent(context.runtimeId(), ignored -> new AtomicLong()).incrementAndGet();
        events.add(new OrchestrationEvent(sequence, type, context.reviewId().value().toString(), context.attemptNo(),
                stage, actor, referenceVersion, Instant.now()));
        eventPublisher.publish(new ai.cc.chongming.review.domain.event.ReviewEventDraft(
                context.reviewId(),
                context.attemptNo(),
                toBusinessEventType(type),
                stage,
                RoleType.valueOf(actor),
                null,
                null,
                null,
                null,
                null,
                progressFor(stage),
                null,
                1,
                Map.of("referenceVersion", Long.toString(referenceVersion))));
    }

    private ai.cc.chongming.review.domain.event.ReviewEventType toBusinessEventType(OrchestrationEventType type) {
        return switch (type) {
            case PLAN_CREATED -> ai.cc.chongming.review.domain.event.ReviewEventType.PLAN_CREATED;
            case PLAN_REVISED -> ai.cc.chongming.review.domain.event.ReviewEventType.PLAN_REVISED;
            case ROLE_ACTIVATED -> ai.cc.chongming.review.domain.event.ReviewEventType.ROLE_ACTIVATED;
            case CANCELLED -> ai.cc.chongming.review.domain.event.ReviewEventType.REVIEW_CANCELLED;
        };
    }

    private Integer progressFor(ReviewStage stage) {
        return switch (stage) {
            case PLANNING -> 20;
            case INITIAL_REVIEW -> 40;
            case CANCELLED -> 100;
            default -> null;
        };
    }

    private void requirePlanningReview(Review review, ReviewRuntimeContext context) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(context, "runtimeContext must not be null");
        if (!review.id().equals(context.reviewId()) || review.attemptNo() != context.attemptNo()) {
            throw new IllegalArgumentException("runtime context must identify the current review attempt");
        }
        if (review.stage() != ReviewStage.PLANNING) {
            throw new IllegalStateException("orchestration can start only from PLANNING");
        }
    }

    private List<String> validateTasks(List<String> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("publicTasks must not be empty");
        }
        return tasks.stream().map(task -> requireText(task, "task")).toList();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Public command to begin the director and required first-round roles.
     *
     * @author wangli
     */
    public record StartRequest(
            Review review,
            ReviewRuntimeContext runtimeContext,
            List<String> publicTasks,
            String changeReason,
            String initialMessage) {

        public StartRequest {
            Objects.requireNonNull(review, "review must not be null");
            Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
            publicTasks = List.copyOf(publicTasks);
            requireText(changeReason, "changeReason");
            requireText(initialMessage, "initialMessage");
        }
    }

    /**
     * Result of creating a director total plan and registering all mandatory initial reviewers.
     *
     * @author wangli
     */
    public record StartResult(
            AgentRuntimeSession session,
            ReviewPlan totalPlan,
            List<RoleActivationService.ActivationReceipt> coreActivations) {

        public StartResult {
            Objects.requireNonNull(session, "session must not be null");
            Objects.requireNonNull(totalPlan, "totalPlan must not be null");
            coreActivations = List.copyOf(coreActivations);
        }
    }

    /**
     * A public revision whose predecessor remains immutable.
     *
     * @author wangli
     */
    public record PlanRevision(int previousVersion, ReviewPlan plan, String reason) {
    }

    /**
     * Ephemeral orchestration event categories. PLAN-010 persists and streams the canonical domain events.
     *
     * @author wangli
     */
    public enum OrchestrationEventType {
        PLAN_CREATED,
        PLAN_REVISED,
        ROLE_ACTIVATED,
        CANCELLED
    }

    /**
     * Process-local observability record that never substitutes for a strong business event.
     *
     * @author wangli
     */
    public record OrchestrationEvent(
            long sequence,
            OrchestrationEventType type,
            String reviewId,
            int attemptNo,
            ReviewStage stage,
            String actor,
            long referenceVersion,
            Instant occurredAt) {
    }
}
