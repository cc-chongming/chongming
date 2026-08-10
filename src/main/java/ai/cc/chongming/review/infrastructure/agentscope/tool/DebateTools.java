package ai.cc.chongming.review.infrastructure.agentscope.tool;

import ai.cc.chongming.review.application.ClaimService;
import ai.cc.chongming.review.application.DebateService;
import ai.cc.chongming.review.application.JudgeService;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.Review;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Server-side facade for future AgentScope tool registration; all calls retain typed commands and domain guards.
 *
 * @author wangli
 */
@Component
public class DebateTools {

    private final ClaimService claimService;
    private final DebateService debateService;
    private final JudgeService judgeService;

    public DebateTools(ClaimService claimService, DebateService debateService, JudgeService judgeService) {
        this.claimService = Objects.requireNonNull(claimService, "claimService must not be null");
        this.debateService = Objects.requireNonNull(debateService, "debateService must not be null");
        this.judgeService = Objects.requireNonNull(judgeService, "judgeService must not be null");
    }

    public ClaimService.ClaimSubmissionResult submitClaim(Review review, ClaimService.ClaimSubmission command) {
        return claimService.submit(review, command);
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Batch topic registration replaces the old single-topic open path.
     */
    public DebateService.RegisterTopicsResult registerDebateTopics(Review review, DebateToolCommands.RegisterTopics command) {
        return debateService.registerTopics(review, command);
    }

    public DebateService.TurnResult submitChallenge(Review review, DebateToolCommands.Challenge command) {
        return debateService.submitChallenge(review, command);
    }

    public DebateService.TurnResult submitRebuttal(Review review, DebateToolCommands.Rebuttal command) {
        return debateService.submitRebuttal(review, command);
    }

    public DebateService.TurnResult changePosition(Review review, DebateToolCommands.PositionChange command) {
        return debateService.changePosition(review, command);
    }

    public DebateService.TurnResult requestAdditionalEvidence(Review review, DebateToolCommands.EvidenceRequest command) {
        return debateService.requestAdditionalEvidence(review, command);
    }

    public DebateService.TopicResult closeDebateTopic(Review review, DebateToolCommands.CloseTopic command) {
        return debateService.closeTopic(review, command);
    }

    public JudgeService.JudgeResult submitJudgement(Review review, JudgeService.JudgeSubmission command) {
        return judgeService.submitJudgement(review, command);
    }

    public GateDecision draftGate(Review review) {
        return judgeService.draftGate(review);
    }

    public List<ai.cc.chongming.review.domain.model.Claim> publishInitialClaims(Review review) {
        return claimService.publishInitialClaims(review);
    }
}
