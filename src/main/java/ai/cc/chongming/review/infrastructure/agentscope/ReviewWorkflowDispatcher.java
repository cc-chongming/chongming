package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.DebateConvergenceGuard;
import ai.cc.chongming.review.application.DebateFocusResolver;
import ai.cc.chongming.review.application.ReviewDispatchService;
import ai.cc.chongming.review.application.ReviewEventListener;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.config.AgentScopeProperties;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand;
import ai.cc.chongming.review.domain.model.ReviewDispatchCommand.DispatchedAction;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import ai.cc.chongming.review.domain.model.ReviewTypes.IdempotencyKey;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewCommandMetadata;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * [AIREVIEW-PLAN-009#1.4][AIREVIEW-PLAN-024#方案3] Delivers only server-verified directed
 * dispatch envelopes after committed business events. The former broadcast of generic debate
 * prompts to every role is removed: the Director issues dispatch commands through validated
 * server tools, and this dispatcher injects the exact same envelope into the target role's
 * context. It never changes review state itself.
 *
 * @author wangli
 */
@Component
public class ReviewWorkflowDispatcher implements ReviewEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewWorkflowDispatcher.class);

    /** How long the server-generated rebuttal envelope stays consumable after a challenge. */
    private static final Duration REBUTTAL_DISPATCH_TTL = Duration.ofMinutes(10);

    /**
     * [AIREVIEW-PLAN-046#1] How long a server-generated CHALLENGE envelope stays consumable after a
     * topic opens with both sides or a defence SUPPORT claim lands. 20 minutes deliberately exceeds
     * the rebuttal TTL so objectors have room to interrogate before the server reclaims the envelope.
     */
    private static final Duration CHALLENGE_DISPATCH_TTL = Duration.ofMinutes(20);

    /** [AIREVIEW-PLAN-046#1] Server challenge against a SUPPORT claim mounted before the topic opened. */
    private static final String SERVER_CHALLENGE_AFTER_OPPOSITION = "SERVER_CHALLENGE_AFTER_OPPOSITION";

    /** [AIREVIEW-PLAN-046#1] Server challenge after a defence SUPPORT claim completed both sides. */
    private static final String SERVER_CHALLENGE_AFTER_DEFENSE = "SERVER_CHALLENGE_AFTER_DEFENSE";

    /** [AIREVIEW-PLAN-059#4] Server challenge re-armed when the serial focus advances to a two-sided never-challenged topic. */
    private static final String SERVER_CHALLENGE_ON_FOCUS_ADVANCE = "SERVER_CHALLENGE_ON_FOCUS_ADVANCE";

    private final ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider;
    private final ReviewRegistry reviewRegistry;
    private final ReviewDispatchService dispatchService;
    private final ReviewDebateStore debateStore;
    private final ObjectProvider<DebateConvergenceGuard> convergenceGuardProvider;
    private final AgentScopeProperties properties;
    private final ConcurrentMap<String, reactor.core.publisher.Sinks.Many<Dispatch>> queues = new ConcurrentHashMap<>();

    public ReviewWorkflowDispatcher(ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider) {
        this(runtimeAdapterProvider, null, null, null, null, null);
    }

    public ReviewWorkflowDispatcher(
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ReviewRegistry reviewRegistry,
            ReviewDispatchService dispatchService,
            ReviewDebateStore debateStore) {
        this(runtimeAdapterProvider, reviewRegistry, dispatchService, debateStore, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewWorkflowDispatcher(
            ObjectProvider<AgentRuntimeAdapter> runtimeAdapterProvider,
            ReviewRegistry reviewRegistry,
            ReviewDispatchService dispatchService,
            ReviewDebateStore debateStore,
            ObjectProvider<DebateConvergenceGuard> convergenceGuardProvider,
            AgentScopeProperties properties) {
        this.runtimeAdapterProvider = Objects.requireNonNull(runtimeAdapterProvider, "runtimeAdapterProvider must not be null");
        this.reviewRegistry = reviewRegistry;
        this.dispatchService = dispatchService;
        this.debateStore = debateStore;
        this.convergenceGuardProvider = convergenceGuardProvider;
        this.properties = properties;
    }

    /** [AIREVIEW-PLAN-059#4] properties 缺失（旧构造/测试缝）时按配置默认值视为串行开启。 */
    private boolean serialEnabled() {
        return properties == null || properties.debateSerialTopics();
    }

    /** [AIREVIEW-PLAN-059#4] 当前焦点议题 id 文本；无焦点或 store 缺失时为 "-"。 */
    private String focusIdOf(ReviewEvent event) {
        if (debateStore == null) {
            return "-";
        }
        return DebateFocusResolver.focus(debateStore, event.reviewId())
                .map(topic -> topic.id().value().toString()).orElse("-");
    }

    @Override
    public void onCommitted(ReviewEvent event) {
        if (event.type() == ReviewEventType.REVIEW_CANCELLED || event.type() == ReviewEventType.REVIEW_FAILED) {
            clearGuard(event);
            reactor.core.publisher.Sinks.Many<Dispatch> queue = queues.remove(runtimeId(event));
            if (queue != null) {
                queue.tryEmitComplete();
            }
            rejectPendingCommands(event, "REVIEW_TERMINATED");
        } else if (event.type() == ReviewEventType.INITIAL_REVIEW_COMPLETED) {
            // [AIREVIEW-PLAN-024#方案4] Conflict detection is deterministic: recall candidates,
            // batch-register every chosen subject in one command, or skip when none remains.
            send(runtimeId(event), directorLabel(event), "All core initial reviews are complete. First call list_persisted_claims, then list_conflict_candidates. If at least one conflict candidate remains, register ALL chosen candidates in one register_topics batch command; only when no conflict candidate remains call skip_debate_when_no_conflicts. A single GAP or UNKNOWN assessment alone is never a debate topic. Do not search the workspace for Claim files or create facts in text.");
        } else if (event.type() == ReviewEventType.DEBATE_TOPIC_OPENED) {
            // [AIREVIEW-PLAN-046#1] Opening a two-sided topic is the first server-side challenge
            // trigger; the Director wake below states that challenges are server-issued.
            issueChallengeDispatches(event, event.topicId(), SERVER_CHALLENGE_AFTER_OPPOSITION);
            // [AIREVIEW-PLAN-059#4] 串行辩论：唤醒附当前焦点，协调者仅驱动焦点议题。
            wakeDirector(event, "A debate topic opened. Direct the debate exclusively through dispatch_debate_action: issue one directed dispatch command per intended write action (recipientRole, allowedAction, topicId, and the target Claim or Turn). The server validates and delivers each envelope; never instruct roles with free text and never grant an action beyond one command. challenges are server-issued; do not dispatch CHALLENGE yourself."
                    + " （串行辩论）当前焦点议题=" + focusIdOf(event)
                    + "：仅为焦点议题签发 DEFENSE 等调度命令；其余议题服务端排队，焦点终态后自动前进。");
        } else if (event.type() == ReviewEventType.CLAIM_SUBMITTED) {
            // [AIREVIEW-PLAN-046#1] A SUPPORT claim committed during a debate round can complete the
            // defence side of an objector-only topic; the server then issues the CHALLENGE envelopes.
            issueChallengeAfterDefenseClaim(event);
        } else if (event.type() == ReviewEventType.DEBATE_ROUND_2_STARTED) {
            // [AIREVIEW-PLAN-047#1] Round two is topic-level: only the named topic advances; the
            // review stays in the single DEBATE phase while every other topic keeps its own round.
            wakeDirector(event, "Debate round two started only for topic "
                    + (event.topicId() == null ? "-" : event.topicId().value())
                    + " (仅该议题进入第二轮，其余议题保持各自轮次，评审仍处于 DEBATE 阶段). "
                    + "Issue dispatch_debate_action commands for every still-required round-two action on that "
                    + "topic with the matching targets, or close it with close_debate_topic and converge with "
                    + "begin_judging when nothing further is required. Do not run an empty round.");
        } else if (event.type() == ReviewEventType.DEBATE_TOPIC_CLOSED) {
            // [AIREVIEW-PLAN-059#4] 焦点前进：唤醒附下一焦点议题；新焦点双方齐备但从未质询时补发质询。
            Optional<DebateTopic> nextFocus = debateStore == null
                    ? Optional.empty() : DebateFocusResolver.focus(debateStore, event.reviewId());
            String focusText = nextFocus.map(topic -> "下一焦点议题=" + topic.id().value()
                    + "，请围绕它继续辩论（为它签发 DEFENSE 等调度）；其余议题保持排队。 ")
                    .orElse("所有议题已终态，使用 begin_judging。 ");
            wakeDirector(event, "A debate topic was closed. " + focusText
                    + "If more topics still need their second round, open it per topic with begin_second_round(topicId) (议题级，仅该议题进入第二轮); when every topic is terminal, use begin_judging.");
            nextFocus.ifPresent(topic -> issueChallengeDispatches(event, topic.id(), SERVER_CHALLENGE_ON_FOCUS_ADVANCE));
        } else if (event.type() == ReviewEventType.CHALLENGE_SUBMITTED) {
            issueRebuttalDispatch(event);
            wakeDirector(event, "A debate turn was committed. Review the public context and decide whether to close the topic, begin_second_round(topicId) for that topic alone, or continue the bounded debate.");
        } else if (event.type() == ReviewEventType.REBUTTAL_SUBMITTED
                || event.type() == ReviewEventType.POSITION_CHANGED
                || event.type() == ReviewEventType.EVIDENCE_REQUESTED) {
            wakeDirector(event, "A debate turn was committed. Review the public context and decide whether to close the topic, begin_second_round(topicId) for that topic alone, or continue the bounded debate.");
        } else if (event.type() == ReviewEventType.DISPATCH_COMMAND_ISSUED) {
            deliverDispatchEnvelope(event);
        } else if (event.type() == ReviewEventType.DISPATCH_COMMAND_EXPIRED
                || event.type() == ReviewEventType.DISPATCH_COMMAND_REJECTED) {
            LOGGER.info("REVIEW_DISPATCH_COMMAND_DROPPED reviewId={} type={} commandId={} reason={}",
                    event.reviewId().value(), event.type(),
                    event.payload().getOrDefault("commandId", "-"),
                    event.payload().getOrDefault("reason", "-"));
            wakeDirector(event, "Dispatch command "
                    + event.payload().getOrDefault("commandId", "-") + " ("
                    + event.payload().getOrDefault("allowedAction", "-") + " for "
                    + event.payload().getOrDefault("recipientRole", "-") + ") was dropped: "
                    + event.payload().getOrDefault("reason", event.type().name())
                    + ". Reissue a valid dispatch_debate_action command or converge with the stage tools.");
        } else if (event.type() == ReviewEventType.JUDGING_STARTED) {
            clearGuard(event);
            // The debate is over; stop any role subagent still grinding through its dispatched run
            // so it stops producing output (and rejected turns) during judging / human decision.
            AgentRuntimeAdapter adapter = runtimeAdapterProvider.getIfAvailable();
            if (adapter != null) {
                adapter.stopRoleRuns(runtimeId(event)).subscribe();
            }
            rejectPendingCommands(event, "JUDGING_STARTED");
            dispatchJudgeForEvent(event);
        } else if (event.type() == ReviewEventType.DEBATE_SKIPPED) {
            clearGuard(event);
            dispatchJudgeForEvent(event);
        }
    }

    public void dispatchJudge(Review review) {
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(review.id(), review.attemptNo());
        send(runtimeId, roleLabel(runtimeId, RoleType.JUDGE),
                "All debate topics are terminal. Use submit_judgement for each topic; if the topic list is empty, skip it. Then always call draft_gate exactly once so the judging stage can finish. Do not add facts.");
    }

    /**
     * [AIREVIEW-PLAN-024#方案4 收口] Single Judge wake point for every path into judging: the
     * Director's begin_judging/skip tools and the server-side forced convergence all publish
     * JUDGING_STARTED or DEBATE_SKIPPED, so the Judge can never be left idle in JUDGING.
     */
    private void dispatchJudgeForEvent(ReviewEvent event) {
        if (reviewRegistry == null) {
            return;
        }
        reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .ifPresent(this::dispatchJudge);
    }

    /**
     * After a committed challenge the server alone issues the rebuttal envelope, addressed to the
     * challenge's target role; other roles never see it and cannot rebut in its place.
     */
    private void issueRebuttalDispatch(ReviewEvent event) {
        if (dispatchService == null || reviewRegistry == null || debateStore == null || event.turnId() == null) {
            return;
        }
        Review review = reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .orElse(null);
        if (review == null) {
            LOGGER.warn("REBUTTAL_DISPATCH_SKIPPED reviewId={} reason=REVIEW_NOT_FOUND", event.reviewId().value());
            return;
        }
        DebateTurn challenge = debateStore.findTurn(event.reviewId(), event.turnId()).orElse(null);
        if (challenge == null || challenge.targetRole() == null) {
            LOGGER.warn("REBUTTAL_DISPATCH_SKIPPED reviewId={} turnId={} reason=CHALLENGE_TURN_NOT_FOUND",
                    event.reviewId().value(), event.turnId().value());
            return;
        }
        // [AIREVIEW-PLAN-059#4] 串行闸：非焦点议题不签发答辩信封。
        if (serialEnabled()) {
            Optional<DebateTopic> focus = DebateFocusResolver.focus(debateStore, review.id());
            if (focus.isPresent() && !focus.get().id().equals(challenge.topicId())) {
                LOGGER.info("REBUTTAL_DISPATCH_SKIPPED reviewId={} turnId={} reason=SERIAL_NOT_FOCUS focusTopicId={}",
                        event.reviewId().value(), event.turnId().value(), focus.get().id().value());
                return;
            }
        }
        try {
            synchronized (review) {
                dispatchService.issue(review, new ReviewDispatchService.DispatchProposal(
                        new ReviewCommandMetadata(review.id(), review.version(),
                                new IdempotencyKey("dispatch:rebuttal:" + challenge.turnId().value())),
                        challenge.targetRole(),
                        DispatchedAction.REBUTTAL,
                        challenge.round(),
                        challenge.topicId(),
                        null,
                        challenge.turnId(),
                        Instant.now().plus(REBUTTAL_DISPATCH_TTL),
                        RoleType.DIRECTOR,
                        "SERVER_REBUTTAL_AFTER_CHALLENGE"));
            }
        } catch (RuntimeException exception) {
            // A failed rebuttal issuance must not abort event delivery; the Director wake above
            // keeps the review moving and the log names the reason.
            LOGGER.warn("REBUTTAL_DISPATCH_ISSUE_FAILED reviewId={} turnId={}",
                    event.reviewId().value(), event.turnId().value(), exception);
        }
    }

    /**
     * [AIREVIEW-PLAN-046#1] CLAIM_SUBMITTED trigger of the unified server-side challenge dispatch:
     * only a SUPPORT claim committed during a debate round that completes the support side of an
     * objector-only topic (its claim is mounted on exactly one topic that had no SUPPORT before)
     * hands over to the shared {@link #issueChallengeDispatches} rule.
     */
    private void issueChallengeAfterDefenseClaim(ReviewEvent event) {
        if (dispatchService == null || reviewRegistry == null || debateStore == null || event.claimId() == null) {
            return;
        }
        // [AIREVIEW-PLAN-047#1] The single DEBATE phase (plus legacy round stages) is the trigger
        // window for server-side challenges after a defence SUPPORT claim lands.
        if (!isDebateStage(event.stage())) {
            return;
        }
        Claim submitted = debateStore.findClaim(event.reviewId(), event.claimId()).orElse(null);
        if (submitted == null || submitted.position() != ClaimPosition.SUPPORT) {
            return;
        }
        TopicId topicId = debateStore.findTopics(event.reviewId()).stream()
                .filter(topic -> topic.claimIds().contains(event.claimId()))
                .map(DebateTopic::id)
                .findFirst()
                .orElse(null);
        if (topicId == null) {
            LOGGER.warn("CHALLENGE_DISPATCH_SKIPPED reviewId={} claimId={} reason=TOPIC_WITH_CLAIM_NOT_FOUND",
                    event.reviewId().value(), event.claimId().value());
            return;
        }
        DebateTopic topic = debateStore.findTopic(event.reviewId(), topicId).orElse(null);
        if (topic == null) {
            LOGGER.warn("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=TOPIC_NOT_FOUND",
                    event.reviewId().value(), topicId.value());
            return;
        }
        // Only the claim that first completes the support side triggers the server dispatch; a later
        // defence claim on an already two-sided topic must not re-arm envelopes.
        boolean mountedSupportBefore = topic.claimIds().stream()
                .filter(claimId -> !claimId.equals(event.claimId()))
                .map(claimId -> debateStore.findClaim(event.reviewId(), claimId).orElse(null))
                .filter(Objects::nonNull)
                .anyMatch(claim -> claim.position() == ClaimPosition.SUPPORT
                        && claim.status() != ClaimStatus.WITHDRAWN);
        if (mountedSupportBefore) {
            LOGGER.info("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} claimId={} reason=SUPPORT_ALREADY_PRESENT",
                    event.reviewId().value(), topicId.value(), event.claimId().value());
            return;
        }
        issueChallengeDispatches(event, topicId, SERVER_CHALLENGE_AFTER_DEFENSE);
    }

    /**
     * [AIREVIEW-PLAN-046#1] Unified server-side challenge dispatch. When a debate-round topic holds
     * at least one SUPPORT and one OPPOSE claim and no CHALLENGE turn has ever been committed, the
     * server issues one CHALLENGE envelope per distinct OPPOSE role (never to the target SUPPORT's
     * own role), targeting the topic's highest-severity SUPPORT claim (P0 most severe; ties resolved
     * by mount order). One idempotency key per (topic, recipient role) keeps both triggers and any
     * coordinator re-dispatch from stacking envelopes, and a single failing recipient is only logged.
     */
    private void issueChallengeDispatches(ReviewEvent event, TopicId topicId, String reason) {
        if (dispatchService == null || reviewRegistry == null || debateStore == null || topicId == null) {
            return;
        }
        Review review = reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .orElse(null);
        if (review == null) {
            LOGGER.warn("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=REVIEW_NOT_FOUND",
                    event.reviewId().value(), topicId.value());
            return;
        }
        if (!isDebateStage(review.stage())) {
            LOGGER.warn("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=STAGE_NOT_DEBATE stage={}",
                    event.reviewId().value(), topicId.value(), review.stage());
            return;
        }
        DebateTopic topic = debateStore.findTopic(review.id(), topicId).orElse(null);
        if (topic == null) {
            LOGGER.warn("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=TOPIC_NOT_FOUND",
                    event.reviewId().value(), topicId.value());
            return;
        }
        if (topic.status().isTerminal()) {
            LOGGER.warn("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=TOPIC_TERMINAL status={}",
                    event.reviewId().value(), topicId.value(), topic.status());
            return;
        }
        // [AIREVIEW-PLAN-059#4] 串行闸：仅焦点议题签发质询信封；非焦点议题排队等待焦点前进。
        if (serialEnabled()) {
            Optional<DebateTopic> focus = DebateFocusResolver.focus(debateStore, review.id());
            if (focus.isPresent() && !focus.get().id().equals(topic.id())) {
                LOGGER.info("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=SERIAL_NOT_FOCUS focusTopicId={}",
                        review.id().value(), topicId.value(), focus.get().id().value());
                return;
            }
        }
        // A topic the coordinator already drove into a challenge round (manual dispatch or legacy
        // flow) must never be re-armed: the next envelope is the rebuttal, not another challenge.
        boolean alreadyChallenged = debateStore.findTurns(review.id(), topicId).stream()
                .anyMatch(turn -> turn.turnType() == DebateTurnType.CHALLENGE);
        if (alreadyChallenged) {
            LOGGER.info("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=CHALLENGE_TURN_EXISTS",
                    event.reviewId().value(), topicId.value());
            return;
        }
        // [AIREVIEW-PLAN-047#1] The envelope round follows the topic's own round: a topic whose
        // second round began gets round-two challenge envelopes, everything else round one.
        int round = topic.currentRound() == 2 ? 2 : 1;
        Claim target = highestSeveritySupportClaim(review.id(), topic);
        if (target == null) {
            LOGGER.info("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=NO_SUPPORT_CLAIM",
                    event.reviewId().value(), topicId.value());
            return;
        }
        List<RoleType> recipients = topic.claimIds().stream()
                .map(claimId -> debateStore.findClaim(review.id(), claimId).orElse(null))
                .filter(claim -> claim != null && claim.position() == ClaimPosition.OPPOSE
                        && claim.status() != ClaimStatus.WITHDRAWN)
                .map(Claim::roleType)
                .distinct()
                .filter(role -> role != target.roleType())
                .toList();
        if (recipients.isEmpty()) {
            LOGGER.info("CHALLENGE_DISPATCH_SKIPPED reviewId={} topicId={} reason=NO_OPPOSE_ROLE",
                    event.reviewId().value(), topicId.value());
            return;
        }
        synchronized (review) {
            for (RoleType recipient : recipients) {
                try {
                    dispatchService.issue(review, new ReviewDispatchService.DispatchProposal(
                            new ReviewCommandMetadata(review.id(), review.version(),
                                    new IdempotencyKey("dispatch:challenge:" + topicId.value() + ":" + recipient)),
                            recipient,
                            DispatchedAction.CHALLENGE,
                            round,
                            topicId,
                            target.claimId(),
                            null,
                            Instant.now().plus(CHALLENGE_DISPATCH_TTL),
                            RoleType.DIRECTOR,
                            reason));
                } catch (RuntimeException exception) {
                    // A failed server challenge must not abort the remaining recipients or the event
                    // delivery; the log names the reason and the Director wake keeps the debate moving.
                    LOGGER.warn("CHALLENGE_DISPATCH_ISSUE_FAILED reviewId={} topicId={} recipient={} targetClaimId={}",
                            review.id().value(), topicId.value(), recipient, target.claimId().value(), exception);
                }
            }
        }
    }

    /**
     * [AIREVIEW-PLAN-046#1] Highest-severity SUPPORT claim of the topic (P0 most severe); iterating
     * the topic's claimIds in mount order keeps the first occurrence when severities tie.
     */
    private Claim highestSeveritySupportClaim(ReviewId reviewId, DebateTopic topic) {
        Claim best = null;
        for (ClaimId claimId : topic.claimIds()) {
            Claim candidate = debateStore.findClaim(reviewId, claimId).orElse(null);
            if (candidate == null || candidate.position() != ClaimPosition.SUPPORT
                    || candidate.status() == ClaimStatus.WITHDRAWN) {
                continue;
            }
            if (best == null || candidate.severity().ordinal() < best.severity().ordinal()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * [AIREVIEW-PLAN-047#1] The single DEBATE phase plus the legacy round stages are all "debate"
     * for challenge/rebuttal dispatch and stage-gating; per-topic rounds come from
     * {@code DebateTopic.currentRound()}.
     */
    private static boolean isDebateStage(ReviewStage stage) {
        return stage == ReviewStage.DEBATE
                || stage == ReviewStage.DEBATE_ROUND_1
                || stage == ReviewStage.DEBATE_ROUND_2;
    }

    /** Injects the persisted envelope into exactly the recipient role's context. */
    private void deliverDispatchEnvelope(ReviewEvent event) {
        if (dispatchService == null) {
            return;
        }
        String commandIdText = event.payload().get("commandId");
        if (commandIdText == null || commandIdText.isBlank()) {
            LOGGER.warn("REVIEW_DISPATCH_ENVELOPE_MISSING_COMMAND_ID reviewId={}", event.reviewId().value());
            return;
        }
        ReviewDispatchCommand command = dispatchService
                .find(event.reviewId(), new ReviewDispatchCommand.CommandId(UUID.fromString(commandIdText)))
                .orElse(null);
        if (command == null) {
            LOGGER.warn("REVIEW_DISPATCH_ENVELOPE_COMMAND_NOT_FOUND reviewId={} commandId={}",
                    event.reviewId().value(), commandIdText);
            return;
        }
        if (command.status() != ReviewDispatchCommand.DispatchCommandStatus.PENDING) {
            LOGGER.info("REVIEW_DISPATCH_ENVELOPE_SKIPPED commandId={} status={}",
                    command.commandId().value(), command.status());
            return;
        }
        if (command.isExpiredAt(Instant.now())) {
            LOGGER.info("REVIEW_DISPATCH_ENVELOPE_EXPIRED_BEFORE_DELIVERY commandId={}", command.commandId().value());
            return;
        }
        String runtimeId = ReviewRuntimeContext.runtimeIdFor(command.reviewId(), command.attemptNo());
        String recipient = roleLabel(runtimeId, command.recipientRole());
        LOGGER.info("REVIEW_DISPATCH_ENVELOPE_DELIVERING runtimeId={} recipient={} commandId={} action={}",
                runtimeId, recipient, command.commandId().value(), command.allowedAction());
        reactor.core.publisher.Sinks.EmitResult result = queue(runtimeId)
                .tryEmitNext(new Dispatch(recipient, ReviewDispatchService.envelopeText(command), command));
        if (result.isFailure()) {
            // A dropped dispatch would silently miss a directed wake and stall the debate.
            LOGGER.warn("REVIEW_WORKFLOW_DISPATCH_DROPPED runtimeId={} recipient={} commandId={} result={}",
                    runtimeId, recipient, command.commandId().value(), result);
        }
    }

    private void rejectPendingCommands(ReviewEvent event, String reason) {
        if (dispatchService == null || reviewRegistry == null) {
            return;
        }
        reviewRegistry.find(event.reviewId())
                .filter(candidate -> candidate.attemptNo() == event.attemptNo())
                .ifPresent(review -> {
                    try {
                        synchronized (review) {
                            dispatchService.rejectAllPending(review, reason);
                        }
                    } catch (RuntimeException exception) {
                        LOGGER.warn("REVIEW_DISPATCH_REJECT_PENDING_FAILED reviewId={} reason={}",
                                event.reviewId().value(), reason, exception);
                    }
                });
    }

    private void send(String runtimeId, String recipient, String message) {
        reactor.core.publisher.Sinks.EmitResult result = queue(runtimeId).tryEmitNext(new Dispatch(recipient, message, null));
        if (result.isFailure()) {
            // A dropped dispatch would silently miss a Director wake and stall the review.
            LOGGER.warn("REVIEW_WORKFLOW_DISPATCH_DROPPED runtimeId={} recipient={} result={}",
                    runtimeId, recipient, result);
        }
    }

    /**
     * [AIREVIEW-PLAN-024#方案4 收口] Every Director wake during the debate rounds is counted by the
     * convergence guard so a looping Director is deterministically force-converged server-side.
     */
    private void wakeDirector(ReviewEvent event, String message) {
        DebateConvergenceGuard guard = convergenceGuardProvider == null
                ? null : convergenceGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.noteDirectorWake(event.reviewId(), event.attemptNo());
        }
        send(runtimeId(event), directorLabel(event), message);
    }

    private void clearGuard(ReviewEvent event) {
        DebateConvergenceGuard guard = convergenceGuardProvider == null
                ? null : convergenceGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.clear(event.reviewId(), event.attemptNo());
        }
    }

    private reactor.core.publisher.Sinks.Many<Dispatch> queue(String runtimeId) {
        return queues.computeIfAbsent(runtimeId, ignored -> {
            reactor.core.publisher.Sinks.Many<Dispatch> queue = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
            queue.asFlux()
                    .concatMap(dispatch -> deliver(runtimeId, dispatch)
                            .onErrorResume(exception -> {
                                LOGGER.warn("REVIEW_WORKFLOW_DISPATCH_FAILED runtimeId={} recipient={} commandId={}",
                                        runtimeId, dispatch.recipient(),
                                        dispatch.command() == null ? "-" : dispatch.command().commandId().value(),
                                        exception);
                                return reactor.core.publisher.Mono.empty();
                            }))
                    .subscribe();
            return queue;
        });
    }

    private reactor.core.publisher.Mono<Void> deliver(String runtimeId, Dispatch dispatch) {
        AgentRuntimeAdapter adapter = runtimeAdapterProvider.getIfAvailable();
        if (adapter == null) {
            return reactor.core.publisher.Mono.empty();
        }
        if (dispatch.command() != null) {
            return adapter.deliverDispatchCommand(runtimeId, dispatch.recipient(), dispatch.message(), dispatch.command());
        }
        return adapter.send(runtimeId, dispatch.recipient(), dispatch.message());
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
        private final ReviewDispatchCommand command;

        private Dispatch(String recipient, String message, ReviewDispatchCommand command) {
            this.recipient = Objects.requireNonNull(recipient, "recipient must not be null");
            this.message = Objects.requireNonNull(message, "message must not be null");
            this.command = command;
        }

        private String recipient() {
            return recipient;
        }

        private String message() {
            return message;
        }

        private ReviewDispatchCommand command() {
            return command;
        }
    }
}
