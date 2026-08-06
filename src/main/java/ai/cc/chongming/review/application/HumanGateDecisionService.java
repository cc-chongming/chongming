package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionActor;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewProtocolGuard;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.Permission;
import ai.cc.chongming.review.domain.security.ReviewerIdentityProvider.ReviewerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-011#1.3] Finalizes human Gate versions without mutating prior decisions.
 *
 * @author wangli
 */
@Service
public class HumanGateDecisionService {

    private final HumanGateDecisionStore decisionStore;
    private final ReviewDebateStore debateStore;
    private final ReviewerIdentityProvider identityProvider;
    private final ReviewProtocolGuard protocolGuard;
    private final ReviewStateMachine stateMachine;
    private final ReviewEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public HumanGateDecisionService(
            HumanGateDecisionStore decisionStore,
            ReviewDebateStore debateStore,
            ReviewerIdentityProvider identityProvider,
            ReviewProtocolGuard protocolGuard,
            ReviewStateMachine stateMachine,
            ReviewEventPublisher eventPublisher) {
        this(decisionStore, debateStore, identityProvider, protocolGuard, stateMachine, eventPublisher, Clock.systemUTC());
    }

    HumanGateDecisionService(
            HumanGateDecisionStore decisionStore,
            ReviewDebateStore debateStore,
            ReviewerIdentityProvider identityProvider,
            ReviewProtocolGuard protocolGuard,
            ReviewStateMachine stateMachine,
            ReviewEventPublisher eventPublisher,
            Clock clock) {
        this.decisionStore = Objects.requireNonNull(decisionStore, "decisionStore must not be null");
        this.debateStore = Objects.requireNonNull(debateStore, "debateStore must not be null");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider must not be null");
        this.protocolGuard = Objects.requireNonNull(protocolGuard, "protocolGuard must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public synchronized HumanGateDecision finalizeDecision(Review review, FinalDecisionCommand command) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (review.stage() != ReviewStage.WAITING_HUMAN && review.stage() != ReviewStage.NOTIFYING) {
            throw new IllegalStateException("human Gate decision requires WAITING_HUMAN or NOTIFYING");
        }
        if (command.expectedVersion() != review.version()) {
            throw new IllegalStateException("expectedVersion does not match review version");
        }
        ReviewerIdentity reviewer = requireReviewer(command.result());
        GateDecision draft = debateStore.findGateDraft(review.id())
                .orElseThrow(() -> new IllegalStateException("an AI Gate draft is required before human decision"));
        if (protocolGuard.validateGateDecision(DecisionActor.HUMAN, DecisionStatus.FINAL).isValid() == false) {
            throw new IllegalStateException("human Gate decision violates protocol");
        }
        HumanGateDecision previous = decisionStore.findLatest(review.id()).orElse(null);
        HumanGateDecision decision = new HumanGateDecision(
                review.id(),
                previous == null ? 1L : previous.gateVersion() + 1L,
                command.result(),
                command.reason(),
                command.conditions(),
                command.overrideReason(),
                reviewer.reviewerId(),
                previous == null ? null : previous.gateVersion(),
                clock.instant());
        decisionStore.append(decision);
        if (review.stage() == ReviewStage.WAITING_HUMAN) {
            review.transitionTo(stateMachine, ReviewStage.NOTIFYING);
        } else {
            review.recordFinalGateRevision();
        }
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review,
                ReviewEventType.HUMAN_GATE_FINALIZED,
                RoleType.DIRECTOR,
                null,
                null,
                null,
                null,
                null,
                95,
                Map.of(
                        "gateVersion", Long.toString(decision.gateVersion()),
                        "result", decision.result().name(),
                        "draftResult", draft.result().name())));
        return decision;
    }

    public List<HumanGateDecision> findVersions(Review review) {
        return decisionStore.findVersions(review.id());
    }

    /** Read-only: does not require the review aggregate to be registered (e.g. after a restart). */
    public List<HumanGateDecision> findVersions(ReviewId reviewId) {
        return decisionStore.findVersions(Objects.requireNonNull(reviewId, "reviewId must not be null"));
    }

    private ReviewerIdentity requireReviewer(GateResult result) {
        ReviewerIdentity reviewer = identityProvider.currentReviewer();
        if (reviewer == null || !reviewer.canReview()) {
            throw new SecurityException("current identity is not allowed to finalize a Gate decision");
        }
        if (result == GateResult.OVERRIDE && !reviewer.permissions().contains(Permission.OVERRIDE)) {
            throw new SecurityException("current identity is not allowed to override a Gate decision");
        }
        return reviewer;
    }

    /**
     * @author wangli
     */
    public record FinalDecisionCommand(
            long expectedVersion,
            GateResult result,
            String reason,
            List<String> conditions,
            String overrideReason) {

        public FinalDecisionCommand {
            Objects.requireNonNull(result, "result must not be null");
            conditions = List.copyOf(conditions == null ? List.of() : conditions);
        }
    }
}
