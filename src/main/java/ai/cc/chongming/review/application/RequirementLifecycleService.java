package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * [AIREVIEW-PLAN-021#3] Applies committed review facts to an associated requirement without feeding changes back into review commands.
 *
 * @author zyj
 */
@Service
public class RequirementLifecycleService implements ReviewEventListener {

    private final RequirementCommandService commandService;
    private final RequirementRepository requirementRepository;

    public RequirementLifecycleService(
            RequirementCommandService commandService,
            RequirementRepository requirementRepository) {
        this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
    }

    @Override
    public void onCommitted(ReviewEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        requirementRepository.findByReviewId(event.reviewId()).ifPresent(requirement -> apply(event, requirement.status()));
    }

    private void apply(ReviewEvent event, RequirementStatus currentStatus) {
        if (event.type() == ReviewEventType.PLAN_CREATED && currentStatus == RequirementStatus.PENDING_REVIEW) {
            commandService.markReviewStarted(event.reviewId());
            return;
        }
        if (event.type() == ReviewEventType.HUMAN_GATE_FINALIZED && currentStatus == RequirementStatus.REVIEWING) {
            GateResult result = readGateResult(event);
            if (result != null) {
                commandService.applyGateDecision(event.reviewId(), result);
            }
        }
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
