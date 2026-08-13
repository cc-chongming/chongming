package ai.cc.chongming.task.application;

import ai.cc.chongming.review.application.ReviewEventListener;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementFilter;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementPage;
import ai.cc.chongming.task.domain.DevTask;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.repository.DevTaskRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions exactly one PENDING_ASSIGN development task per requirement whose final human
 * Gate decision passes. Listener failures are isolated from the Gate decision itself: any
 * provisioning error is logged and swallowed, mirroring the notification outbox listener.
 *
 * @author wangli
 */
@Service
public class DevTaskProvisioningListener implements ReviewEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DevTaskProvisioningListener.class);

    private static final Set<GateResult> PASS_FAMILY =
            EnumSet.of(GateResult.AI_PASS, GateResult.CONDITIONAL, GateResult.PASS, GateResult.OVERRIDE);

    /**
     * Provisioning is only meaningful while the requirement can still be developed. REVIEWING
     * is tolerated because listener delivery may race ahead of the Gate status transition;
     * REJECTED/RETURNED/DONE/CANCELLED requirements never receive a task, even when a stale
     * pass event is replayed.
     */
    private static final Set<RequirementStatus> PROVISIONABLE_STATUSES =
            EnumSet.of(RequirementStatus.APPROVED, RequirementStatus.REVIEWING);

    private static final int RECONCILE_PAGE_SIZE = 50;

    private final RequirementRepository requirementRepository;
    private final DevTaskRepository devTaskRepository;

    public DevTaskProvisioningListener(
            RequirementRepository requirementRepository,
            DevTaskRepository devTaskRepository) {
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.devTaskRepository = Objects.requireNonNull(devTaskRepository, "devTaskRepository must not be null");
    }

    @Override
    public void onCommitted(ReviewEvent event) {
        if (event == null || event.type() != ReviewEventType.HUMAN_GATE_FINALIZED) {
            return;
        }
        try {
            provisionForFinalizedGate(event);
        } catch (RuntimeException exception) {
            LOGGER.error("Unable to provision dev task for finalized review {}", event.reviewId().value(), exception);
        }
    }

    /**
     * Backfills PENDING_ASSIGN tasks for approved requirements that missed event-driven
     * provisioning, returning how many tasks were created.
     */
    @Transactional
    public long reconcile() {
        long created = 0L;
        int page = 1;
        RequirementFilter filter = new RequirementFilter(RequirementStatus.APPROVED, null, null);
        while (true) {
            RequirementPage result = requirementRepository.findPage(filter, page, RECONCILE_PAGE_SIZE);
            List<Requirement> items = result.items();
            for (Requirement requirement : items) {
                if (createTaskIfAbsent(requirement)) {
                    created++;
                }
            }
            if (items.size() < RECONCILE_PAGE_SIZE || (long) page * RECONCILE_PAGE_SIZE >= result.total()) {
                break;
            }
            page++;
        }
        return created;
    }

    private void provisionForFinalizedGate(ReviewEvent event) {
        GateResult result = readGateResult(event);
        if (result == null || !PASS_FAMILY.contains(result)) {
            return;
        }
        requirementRepository.findByReviewId(event.reviewId()).ifPresent(this::createTaskIfAbsent);
    }

    private boolean createTaskIfAbsent(Requirement requirement) {
        if (!PROVISIONABLE_STATUSES.contains(requirement.status())) {
            return false;
        }
        if (devTaskRepository.findByRequirementId(requirement.id()).isPresent()) {
            return false;
        }
        devTaskRepository.save(DevTask.draft(
                new DevTaskId(UUID.randomUUID()),
                requirement.id(),
                requirement.reviewId(),
                requirement.title()));
        return true;
    }

    private GateResult readGateResult(ReviewEvent event) {
        String value = event.payload().get("result");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return GateResult.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
