package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeAdapter;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeRoleRequest;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeSession;
import ai.cc.chongming.review.infrastructure.agentscope.AgentRuntimeStartRequest;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reattaches an attempt to its stable director and role session labels without replaying domain commands.
 *
 * @author wangli
 */
@Service
public class ReviewRecoveryService {

    private final AgentRuntimeAdapter runtimeAdapter;
    private final ReviewWorkspaceLayout workspaceLayout;

    public ReviewRecoveryService(AgentRuntimeAdapter runtimeAdapter, ReviewWorkspaceLayout workspaceLayout) {
        this.runtimeAdapter = Objects.requireNonNull(runtimeAdapter, "runtimeAdapter must not be null");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
    }

    /**
     * Resumes an in-process runtime or recreates its handles against the same persisted AgentScope sessions.
     */
    public Mono<RecoveryResult> recover(Review review, ReviewRuntimeContext context) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!review.id().equals(context.reviewId()) || review.attemptNo() != context.attemptNo()) {
            return Mono.error(new IllegalArgumentException("runtime context must identify the current review attempt"));
        }
        if (review.stage().isTerminal()) {
            return Mono.error(new IllegalStateException("terminal reviews cannot be resumed"));
        }
        workspaceLayout.open(context);
        return runtimeAdapter.resume(context.runtimeId())
                .map(session -> new RecoveryResult(session, false, review.roleActivations().stream()
                        .map(activation -> activation.agentLabel()).toList()))
                .onErrorResume(IllegalArgumentException.class, ignored -> rehydrate(review, context));
    }

    private Mono<RecoveryResult> rehydrate(Review review, ReviewRuntimeContext context) {
        AgentRuntimeStartRequest startRequest = new AgentRuntimeStartRequest(
                context.runtimeId(), context.userId(), context.directorSessionId(),
                "Resume the persisted review attempt without repeating committed business actions.", context);
        return runtimeAdapter.start(startRequest).flatMap(session -> Flux.fromIterable(review.roleActivations())
                .concatMap(activation -> runtimeAdapter.registerRole(new AgentRuntimeRoleRequest(
                        context.runtimeId(),
                        context,
                        activation.roleType(),
                        activation.agentLabel(),
                        context.roleSessionId(activation.roleType()))))
                .then(Mono.just(new RecoveryResult(
                        session,
                        true,
                        review.roleActivations().stream().map(activation -> activation.agentLabel()).toList()))));
    }

    /**
     * Stable recovery result that identifies whether a new in-memory adapter handle was reconstructed.
     *
     * @author wangli
     */
    public record RecoveryResult(AgentRuntimeSession session, boolean rehydrated, List<String> restoredRoleLabels) {

        public RecoveryResult {
            Objects.requireNonNull(session, "session must not be null");
            restoredRoleLabels = List.copyOf(restoredRoleLabels);
        }
    }
}
