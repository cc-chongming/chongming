package ai.cc.chongming.review.agentscope;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewRecoveryService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleActivation;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.support.FakeAgentRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies recovery rehydrates stable runtime identities without replaying domain role activation commands.
 *
 * @author wangli
 */
class ReviewRecoveryServiceTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rehydratesMissingRuntimeThenResumesExistingHandle() {
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        review.transitionTo(stateMachine, ReviewStage.SNAPSHOTTING);
        review.transitionTo(stateMachine, ReviewStage.PLANNING);
        review.transitionTo(stateMachine, ReviewStage.INITIAL_REVIEW);
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                review.id(), review.attemptNo(), "user-001", "trace-001", IntakeCancellation.neverCancelled());
        review.activateRole(new RoleActivation(RoleType.PRODUCT, context.roleLabel(RoleType.PRODUCT), false));
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter();
        ReviewRecoveryService service = new ReviewRecoveryService(
                adapter,
                new ReviewWorkspaceLayout(new ReviewProperties(temporaryDirectory.toString(), 8, 2), new ObjectMapper()));

        ReviewRecoveryService.RecoveryResult rehydrated = service.recover(review, context).block();
        ReviewRecoveryService.RecoveryResult resumed = service.recover(review, context).block();

        assertThat(rehydrated.rehydrated()).isTrue();
        assertThat(rehydrated.restoredRoleLabels()).containsExactly(context.roleLabel(RoleType.PRODUCT));
        assertThat(resumed.rehydrated()).isFalse();
        assertThat(review.roleActivations()).hasSize(1);
    }
}
