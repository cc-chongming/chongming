package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.ReviewEventListener;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * [AIREVIEW-PLAN-009#1.4] Sends stage-specific public instructions only after a committed
 * business event. It never changes review state itself.
 *
 * @author wangli
 */
@Component
public class ReviewWorkflowDispatcher implements ReviewEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewWorkflowDispatcher.class);
    private final ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider;
    private final ConcurrentMap<String, reactor.core.publisher.Sinks.Many<Dispatch>> queues = new ConcurrentHashMap<>();

    public ReviewWorkflowDispatcher(ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider) {
        this.runtimeAdapterProvider = Objects.requireNonNull(runtimeAdapterProvider, "runtimeAdapterProvider must not be null");
    }

    @Override
    public void onCommitted(ReviewEvent event) {
        if (event.type() == ReviewEventType.REVIEW_CANCELLED || event.type() == ReviewEventType.REVIEW_FAILED) {
            reactor.core.publisher.Sinks.Many<Dispatch> queue = queues.remove(runtimeId(event));
            if (queue != null) {
                queue.tryEmitComplete();
            }
        } else if (event.type() == ReviewEventType.INITIAL_REVIEW_COMPLETED) {
            send(runtimeId(event), directorLabel(event), "All core initial reviews are complete. First call list_persisted_claims. If any persisted Claim has an OPPOSE position, open debate topic(s) for those conflicting Claims; only when no persisted Claim has an OPPOSE position call skip_debate_when_no_conflicts. Do not search the workspace for Claim files or create facts in text.");
        } else if (event.type() == ReviewEventType.DEBATE_TOPIC_OPENED) {
            dispatchRound(event, 1);
        } else if (event.type() == ReviewEventType.DEBATE_TOPIC_CLOSED) {
            send(runtimeId(event), directorLabel(event), "A debate topic was closed. If more topics need round one or two, use the stage tools; when every topic is terminal, use begin_judging.");
        } else if (event.type() == ReviewEventType.CHALLENGE_SUBMITTED
                || event.type() == ReviewEventType.REBUTTAL_SUBMITTED
                || event.type() == ReviewEventType.POSITION_CHANGED
                || event.type() == ReviewEventType.EVIDENCE_REQUESTED) {
            send(runtimeId(event), directorLabel(event), "A debate turn was committed. Review the public context and decide whether to close the topic, start round two, or continue the bounded debate.");
        } else if (event.type() == ReviewEventType.JUDGING_STARTED) {
            // The debate is over; stop any role subagent still grinding through its dispatched run
            // so it stops producing output (and rejected turns) during judging / human decision.
            AgentRuntimeAdapter adapter = runtimeAdapterProvider.getIfAvailable();
            if (adapter != null) {
                adapter.stopRoleRuns(runtimeId(event)).subscribe();
            }
        }
    }

    public void dispatchRound(Review review, int round) {
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        dispatchRound(runtimeId, review.roleActivations().stream().map(activation -> activation.roleType()).toList(), round);
    }

    private void dispatchRound(ReviewEvent event, int round) {
        dispatchRound(runtimeId(event), List.of(RoleType.PRODUCT, RoleType.PROJECT, RoleType.FRONTEND, RoleType.BACKEND), round);
    }

    private void dispatchRound(String runtimeId, List<RoleType> roles, int round) {
        Flux.fromIterable(roles)
                .filter(role -> role != RoleType.JUDGE && role != RoleType.DIRECTOR)
                .concatMap(role -> sendAsync(runtimeId, roleLabel(runtimeId, role),
                        "Debate round " + round + " is active. First call list_persisted_debate_topics to inspect the persisted topics and their turns. "
                        + "Then match the tool to each topic's status: if a topic is OPEN, use submit_challenge against a Claim held by another role; "
                        + "if a topic already has a CHALLENGED turn, reply with submit_rebuttal and pass that turn's turnId from the listing as targetTurnId. "
                        + "Never submit a new challenge on a topic that is already CHALLENGED, and never challenge or rebut your own Claim or turn."))
                .subscribe();
    }

    public void dispatchJudge(Review review) {
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        send(runtimeId, roleLabel(runtimeId, RoleType.JUDGE),
                "All debate topics are terminal. Use submit_judgement for each topic; if the topic list is empty, skip it. Then always call draft_gate exactly once so the judging stage can finish. Do not add facts.");
    }

    private void send(String runtimeId, String recipient, String message) {
        reactor.core.publisher.Sinks.EmitResult result = queue(runtimeId).tryEmitNext(new Dispatch(recipient, message));
        if (result.isFailure()) {
            // A dropped dispatch would silently miss a Director wake and stall the review.
            LOGGER.warn("REVIEW_WORKFLOW_DISPATCH_DROPPED runtimeId={} recipient={} result={}",
                    runtimeId, recipient, result);
        }
    }

    private reactor.core.publisher.Sinks.Many<Dispatch> queue(String runtimeId) {
        return queues.computeIfAbsent(runtimeId, ignored -> {
            reactor.core.publisher.Sinks.Many<Dispatch> queue = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
            queue.asFlux()
                    .concatMap(dispatch -> sendAsync(runtimeId, dispatch.recipient(), dispatch.message())
                            .onErrorResume(exception -> {
                                LOGGER.warn("REVIEW_WORKFLOW_DISPATCH_FAILED runtimeId={} recipient={}", runtimeId, dispatch.recipient(), exception);
                                return reactor.core.publisher.Mono.empty();
                            }))
                    .subscribe();
            return queue;
        });
    }

    private reactor.core.publisher.Mono<Void> sendAsync(String runtimeId, String recipient, String message) {
        AgentRuntimeAdapter adapter = runtimeAdapterProvider.getIfAvailable();
        return adapter == null ? reactor.core.publisher.Mono.empty() : adapter.send(runtimeId, recipient, message);
    }

    private String directorLabel(ReviewEvent event) {
        return runtimeId(event) + "-director";
    }

    private String runtimeId(ReviewEvent event) {
        return ReviewRuntimeContext.runtimeIdFor(event.reviewId(), event.attemptNo());
    }

    private String roleLabel(String runtimeId, RoleType roleType) {
        return runtimeId + "-" + roleType.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** @author wangli */
    private static final class Dispatch {
        private final String recipient;
        private final String message;

        private Dispatch(String recipient, String message) {
            this.recipient = Objects.requireNonNull(recipient, "recipient must not be null");
            this.message = Objects.requireNonNull(message, "message must not be null");
        }

        private String recipient() {
            return recipient;
        }

        private String message() {
            return message;
        }
    }
}
