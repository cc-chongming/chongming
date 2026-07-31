package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.agentscope.ContextScoutHarnessFactory;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewAgUiEventMapper;
import ai.cc.chongming.review.infrastructure.agentscope.ScoutToolTraceCollector;
import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import ai.cc.chongming.review.infrastructure.agentscope.RuntimeTraceRedactor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

/**
 * Runs Context Scout independently from the review state machine for repeatable browser-based
 * diagnosis. Preview output never becomes formal role context or a review-domain fact.
 *
 * @author wangli
 */
@Service
public class ContextScoutPreviewService {

    private static final String PREVIEW_AGENT_PREFIX = "CONTEXT_SCOUT_PREVIEW";
    private static final Duration COMPLETED_PREVIEW_RETENTION = Duration.ofMinutes(10);

    private final ReviewRegistry reviewRegistry;
    private final ReviewCommandService reviewCommandService;
    private final ContextScoutHarnessFactory scoutFactory;
    private final ReviewWorkspaceLayout workspaceLayout;
    private final ReviewRuntimeTraceRegistry traceRegistry;
    private final ReviewAgUiEventMapper agUiEventMapper;
    private final RuntimeTraceRedactor redactor;
    private final ConcurrentMap<String, PreviewRun> runs = new ConcurrentHashMap<>();

    public ContextScoutPreviewService(
            ReviewRegistry reviewRegistry,
            ReviewCommandService reviewCommandService,
            ContextScoutHarnessFactory scoutFactory,
            ReviewWorkspaceLayout workspaceLayout,
            ReviewRuntimeTraceRegistry traceRegistry,
            ReviewAgUiEventMapper agUiEventMapper,
            RuntimeTraceRedactor redactor) {
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.reviewCommandService = Objects.requireNonNull(reviewCommandService, "reviewCommandService must not be null");
        this.scoutFactory = Objects.requireNonNull(scoutFactory, "scoutFactory must not be null");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout must not be null");
        this.traceRegistry = Objects.requireNonNull(traceRegistry, "traceRegistry must not be null");
        this.agUiEventMapper = Objects.requireNonNull(agUiEventMapper, "agUiEventMapper must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    }

    /** Starts an isolated run and returns immediately; clients subscribe to its AG-UI event stream. */
    public PreviewStartResult start(ReviewId reviewId, int attemptNo, String userId, String traceId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        requireText(userId, "userId");
        requireText(traceId, "traceId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Review review = reviewRegistry.find(reviewId)
                .orElseThrow(() -> new PreviewNotFoundException("review does not exist"));
        synchronized (review) {
            if (review.attemptNo() != attemptNo) {
                throw new IllegalStateException("requested Scout preview attempt is not current");
            }
        }
        ReviewRuntimeContext context = new ReviewRuntimeContext(
                reviewId, attemptNo, userId, traceId, IntakeCancellation.neverCancelled());
        reviewCommandService.prepareSnapshotForScoutPreview(reviewId, attemptNo);
        ReviewWorkspaceLayout.ReviewWorkspace workspace = workspaceLayout.open(context);
        String previewId = UUID.randomUUID().toString();
        String runtimeId = context.runtimeId() + ":scout-preview:" + previewId;
        PreviewRun run = new PreviewRun(previewId, runtimeId, reviewId, attemptNo, Instant.now());
        runs.put(previewId, run);
        Schedulers.boundedElastic().schedule(() -> execute(run, context, workspace));
        return run.startResult();
    }

    public ReviewRuntimeTraceRegistry.Subscription subscribe(
            ReviewId reviewId, int attemptNo, String previewId, long afterSequence) {
        PreviewRun run = requireRun(reviewId, attemptNo, previewId);
        return traceRegistry.subscribe(run.runtimeId(), afterSequence);
    }

    public PreviewStatus status(ReviewId reviewId, int attemptNo, String previewId) {
        return requireRun(reviewId, attemptNo, previewId).status();
    }

    public void activate(ReviewRuntimeTraceRegistry.Subscription subscription) {
        traceRegistry.activate(Objects.requireNonNull(subscription, "subscription must not be null"));
    }

    private void execute(PreviewRun run, ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace) {
        run.markRunning();
        String agentId = PREVIEW_AGENT_PREFIX + ":" + run.previewId();
        try (ContextScoutHarnessFactory.PreviewHarness preview =
                scoutFactory.createPreview(context, workspace, run.previewId())) {
            preview.agent().streamEvents(
                            "请建立本仓库的项目概览，并以中文 JSON 输出。",
                            previewAgentContext(context, run.previewId()))
                    .doOnNext(event -> publish(run, context, agentId, preview.toolTraceCollector(), event))
                    .doOnNext(event -> recordVisibleResult(run, context, workspace, event))
                    .then()
                    .block();
            run.markCompleted();
            traceRegistry.publish(run.runtimeId(), new AguiEvent.RunFinished(
                    "review:" + context.reviewId().value(), run.runtimeId()));
        } catch (Throwable exception) {
            run.markFailed(redactor.redactRuntimeError(exception));
            traceRegistry.publish(run.runtimeId(), new AguiEvent.RunError(
                    "review:" + context.reviewId().value(),
                    run.runtimeId(),
                    run.error(),
                    "CONTEXT_SCOUT_PREVIEW_FAILED"));
        } finally {
            releaseAfterReplayWindow(run);
        }
    }

    private void publish(
            PreviewRun run,
            ReviewRuntimeContext context,
            String agentId,
            ScoutToolTraceCollector toolTraceCollector,
            AgentEvent event) {
        List<AguiEvent> events = agUiEventMapper.map(
                event, context, RoleType.DIRECTOR, agentId, run.runtimeId(), toolTraceCollector);
        events.forEach(agUiEvent -> traceRegistry.publish(run.runtimeId(), agUiEvent));
    }

    private void recordVisibleResult(
            PreviewRun run,
            ReviewRuntimeContext context,
            ReviewWorkspaceLayout.ReviewWorkspace workspace,
            AgentEvent event) {
        if (!(event instanceof AgentResultEvent result) || result.getResult() == null) {
            return;
        }
        String visibleResult = redactor.redactVisibleText(result.getResult().getTextContent());
        if (visibleResult == null || visibleResult.isBlank()) {
            return;
        }
        run.setVisibleResult(visibleResult);
        scoutFactory.recordPreviewResult(context, workspace, run.previewId(), visibleResult);
    }

    private PreviewRun requireRun(ReviewId reviewId, int attemptNo, String previewId) {
        requireText(previewId, "previewId");
        PreviewRun run = runs.get(previewId);
        if (run == null || !run.reviewId().equals(reviewId) || run.attemptNo() != attemptNo) {
            throw new PreviewNotFoundException("Scout preview does not exist");
        }
        return run;
    }

    private void releaseAfterReplayWindow(PreviewRun run) {
        Schedulers.boundedElastic().schedule(
                () -> {
                    if (runs.remove(run.previewId(), run)) {
                        traceRegistry.remove(run.runtimeId());
                    }
                },
                COMPLETED_PREVIEW_RETENTION.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Isolates every browser preview from prior Scout calls while preserving the review identity
     * required by AgentScope hooks and trace mapping.
     */
    static RuntimeContext previewAgentContext(ReviewRuntimeContext context, String previewId) {
        Objects.requireNonNull(context, "context must not be null");
        requireText(previewId, "previewId");
        return RuntimeContext.builder()
                .userId(context.userId())
                .sessionId(context.runtimeId() + ":context-scout:preview-" + previewId)
                .put(ReviewRuntimeContext.class, context)
                .build();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record PreviewStartResult(String previewId, String runtimeId, ReviewId reviewId, int attemptNo, Instant createdAt) {
    }

    public record PreviewStatus(
            String previewId,
            String runtimeId,
            String status,
            String visibleResult,
            String error,
            Instant createdAt,
            Instant completedAt) {
    }

    public static final class PreviewNotFoundException extends RuntimeException {
        public PreviewNotFoundException(String message) {
            super(message);
        }
    }

    private static final class PreviewRun {
        private final String previewId;
        private final String runtimeId;
        private final ReviewId reviewId;
        private final int attemptNo;
        private final Instant createdAt;
        private volatile String status = "QUEUED";
        private volatile String visibleResult;
        private volatile String error;
        private volatile Instant completedAt;

        private PreviewRun(String previewId, String runtimeId, ReviewId reviewId, int attemptNo, Instant createdAt) {
            this.previewId = previewId;
            this.runtimeId = runtimeId;
            this.reviewId = reviewId;
            this.attemptNo = attemptNo;
            this.createdAt = createdAt;
        }

        private String previewId() { return previewId; }
        private String runtimeId() { return runtimeId; }
        private ReviewId reviewId() { return reviewId; }
        private int attemptNo() { return attemptNo; }
        private void markRunning() { status = "RUNNING"; }
        private void setVisibleResult(String result) { visibleResult = result; }
        private void markCompleted() { status = "COMPLETED"; completedAt = Instant.now(); }
        private void markFailed(String failure) { status = "FAILED"; error = failure; completedAt = Instant.now(); }
        private String error() { return error; }
        private PreviewStartResult startResult() { return new PreviewStartResult(previewId, runtimeId, reviewId, attemptNo, createdAt); }
        private PreviewStatus status() { return new PreviewStatus(previewId, runtimeId, status, visibleResult, error, createdAt, completedAt); }
    }
}
