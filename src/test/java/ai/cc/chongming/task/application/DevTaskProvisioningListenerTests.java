package ai.cc.chongming.task.application;

import ai.cc.chongming.review.application.RequirementCommandService;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventDraft;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementRepository;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskStatus;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import ai.cc.chongming.task.infrastructure.InMemoryDevTaskRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies event-driven task provisioning: the pass family creates exactly one pending task,
 * rejections create none, duplicate events stay idempotent and provisioning failures never
 * roll back onto the finalized Gate.
 *
 * @author wangli
 */
class DevTaskProvisioningListenerTests {

    private InMemoryRequirementRepository requirementRepository;
    private InMemoryDevTaskRepository devTaskRepository;
    private RequirementCommandService requirementCommandService;
    private DevTaskProvisioningListener listener;

    @BeforeEach
    void setUp() {
        requirementRepository = new InMemoryRequirementRepository();
        devTaskRepository = new InMemoryDevTaskRepository();
        ReviewerIdentityProvider identityProvider =
                () -> new ReviewerIdentityProvider.ReviewerIdentity("alice", Set.of());
        requirementCommandService = new RequirementCommandService(requirementRepository, identityProvider);
        listener = new DevTaskProvisioningListener(requirementRepository, devTaskRepository);
    }

    @Test
    void provisionsPendingAssignTaskWhenFinalGatePasses() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = submittedRequirement(reviewId);

        listener.onCommitted(gateEvent(reviewId, Map.of("result", GateResult.PASS.name())));

        DevTask task = devTaskRepository.findByRequirementId(requirement.id()).orElseThrow();
        assertThat(task.status()).isEqualTo(DevTaskStatus.PENDING_ASSIGN);
        assertThat(task.reviewId()).isEqualTo(reviewId);
        assertThat(task.title()).isEqualTo(requirement.title());
    }

    @Test
    void provisionsForEveryPassFamilyResult() {
        for (GateResult result : new GateResult[] {GateResult.AI_PASS, GateResult.CONDITIONAL, GateResult.OVERRIDE}) {
            InMemoryDevTaskRepository freshTasks = new InMemoryDevTaskRepository();
            DevTaskProvisioningListener freshListener =
                    new DevTaskProvisioningListener(requirementRepository, freshTasks);
            ReviewId reviewId = new ReviewId(UUID.randomUUID());
            Requirement requirement = submittedRequirement(reviewId);

            freshListener.onCommitted(gateEvent(reviewId, Map.of("result", result.name())));

            assertThat(freshTasks.findByRequirementId(requirement.id()))
                    .as("pass family result %s provisions a task", result)
                    .isPresent();
        }
    }

    @Test
    void doesNotProvisionWhenGateBlocksOrReturns() {
        for (GateResult result : new GateResult[] {GateResult.BLOCK, GateResult.RETURN, GateResult.HUMAN_REQUIRED}) {
            ReviewId reviewId = new ReviewId(UUID.randomUUID());
            Requirement requirement = submittedRequirement(reviewId);

            listener.onCommitted(gateEvent(reviewId, Map.of("result", result.name())));

            assertThat(devTaskRepository.findByRequirementId(requirement.id()))
                    .as("result %s must not provision a task", result)
                    .isEmpty();
        }
    }

    @Test
    void doesNotProvisionWhenGateReplaysPassOnAnAlreadyRejectedRequirement() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = submittedRequirement(reviewId);
        requirementCommandService.applyGateDecision(reviewId, GateResult.BLOCK);
        assertThat(requirementRepository.findById(requirement.id()).orElseThrow().status())
                .isEqualTo(RequirementStatus.REJECTED);

        listener.onCommitted(gateEvent(reviewId, Map.of("result", GateResult.PASS.name())));

        assertThat(devTaskRepository.findByRequirementId(requirement.id())).isEmpty();
    }

    @Test
    void ignoresNonGateEventsAndUnknownResults() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = submittedRequirement(reviewId);

        listener.onCommitted(event(reviewId, ReviewEventType.PLAN_CREATED, Map.of()));
        listener.onCommitted(gateEvent(reviewId, Map.of("result", "NOT_A_RESULT")));
        listener.onCommitted(gateEvent(reviewId, Map.of()));

        assertThat(devTaskRepository.findByRequirementId(requirement.id())).isEmpty();
    }

    @Test
    void duplicateFinalizedEventsRemainIdempotent() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = submittedRequirement(reviewId);

        listener.onCommitted(gateEvent(reviewId, Map.of("result", "PASS")));
        listener.onCommitted(gateEvent(reviewId, Map.of("result", "PASS")));

        DevTaskRepository.TaskPage page = devTaskRepository.findPage(null, 1, 100);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).requirementId()).isEqualTo(requirement.id());
    }

    @Test
    void skipsSilentlyWhenNoRequirementIsLinkedToTheReview() {
        assertThatCode(() -> listener.onCommitted(
                gateEvent(new ReviewId(UUID.randomUUID()), Map.of("result", "PASS"))))
                .doesNotThrowAnyException();
        assertThat(devTaskRepository.findPage(null, 1, 100).items()).isEmpty();
    }

    @Test
    void isolatesProvisioningFailuresFromTheFinalizedGate() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        submittedRequirement(reviewId);
        DevTaskRepository failingTasks = new DevTaskRepository() {
            @Override
            public void save(DevTask task) {
                throw new IllegalStateException("storage outage");
            }

            @Override
            public Optional<DevTask> findById(ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId taskId) {
                return Optional.empty();
            }

            @Override
            public Optional<DevTask> findByRequirementId(RequirementId requirementId) {
                return Optional.empty();
            }

            @Override
            public TaskPage findPage(TaskFilter filter, int page, int size) {
                return new TaskPage(java.util.List.of(), page, size, 0L);
            }

            @Override
            public Map<DevTaskStatus, Long> countByStatus() {
                return Map.of();
            }
        };
        DevTaskProvisioningListener failingListener =
                new DevTaskProvisioningListener(requirementRepository, failingTasks);

        assertThatCode(() -> failingListener.onCommitted(gateEvent(reviewId, Map.of("result", "PASS"))))
                .doesNotThrowAnyException();
    }

    @Test
    void reconcileBackfillsApprovedRequirementsWithoutTasks() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = submittedRequirement(reviewId);
        requirementCommandService.markReviewStarted(reviewId);
        requirementCommandService.applyGateDecision(reviewId, GateResult.PASS);

        long firstPass = listener.reconcile();
        long secondPass = listener.reconcile();

        assertThat(firstPass).isEqualTo(1L);
        assertThat(secondPass).isZero();
        assertThat(devTaskRepository.findByRequirementId(requirement.id()))
                .hasValueSatisfying(task -> assertThat(task.status()).isEqualTo(DevTaskStatus.PENDING_ASSIGN));
    }

    private Requirement submittedRequirement(ReviewId reviewId) {
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "任务派发需求", "验证任务派发", "alice", null, "cx-ai", "P1");
        requirementRepository.save(requirement);
        requirementCommandService.submitForReview(requirement.id(), reviewId, requirement.version());
        // Advance to REVIEWING so the provisioning status guard (APPROVED/REVIEWING only) lets
        // the event-driven path through; the final Gate transition decides the outcome later.
        requirementCommandService.markReviewStarted(reviewId);
        return requirementRepository.findById(requirement.id()).orElseThrow();
    }

    private ReviewEvent gateEvent(ReviewId reviewId, Map<String, String> payload) {
        return event(reviewId, ReviewEventType.HUMAN_GATE_FINALIZED, payload);
    }

    private ReviewEvent event(ReviewId reviewId, ReviewEventType type, Map<String, String> payload) {
        return ReviewEvent.committed(1L, new ReviewEventDraft(
                reviewId,
                1,
                type,
                ReviewStage.WAITING_HUMAN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                95,
                payload));
    }
}
