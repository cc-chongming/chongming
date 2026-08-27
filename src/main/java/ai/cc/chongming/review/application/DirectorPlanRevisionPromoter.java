package ai.cc.chongming.review.application;

import ai.cc.chongming.review.infrastructure.agentscope.ReviewWorkspaceLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [AIREVIEW-PLAN-036#闭环] Promotes a Director-authored plan document from the attempt workspace
 * into a bounded public plan revision.
 *
 * <p>The Harness Plan Mode writes one markdown document at `plans/PLAN.md`; the server's
 * own initial plan is a separate `plan-v1.json` artifact written by
 * {@link ReviewOrchestrationService#start} and is deliberately never treated as a revision.
 * Scanning only `PLAN.md`, comparing a content digest against the last promoted document and
 * delegating the revision itself to {@link ReviewOrchestrationService#revisePlan} keeps the
 * promotion idempotent: the same document is promoted at most once, and every distinct content
 * write advances the public plan version. Process-local memory keyed by runtime id records the
 * last promoted digest; a fresh attempt has an empty record and cannot confuse the initial v1.
 *
 * <p>A rejected revision (for example the configured revision bound is reached) is logged and the
 * unchanged document is not retried, so a looping Director cannot hammer the revision path.
 *
 * @author wangli
 */
public final class DirectorPlanRevisionPromoter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectorPlanRevisionPromoter.class);

    /** The single plan document owned by the Harness Plan Mode (`plan_write`). */
    static final String PLAN_DOCUMENT_NAME = "PLAN.md";

    /** Fallback reason when the plan document carries no explicit reason line. */
    static final String DEFAULT_CHANGE_REASON = "协调者修订评审计划（计划文档内容更新）";

    private static final Pattern REASON_LINE = Pattern.compile(
            "^\\s*(?:修订原因|变更原因|修改原因|原因|changeReason|reason)\\s*[:：]?\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(.+?)\\s*$");

    private final ReviewOrchestrationService orchestrationService;
    private final ConcurrentMap<String, PromoterState> states = new ConcurrentHashMap<>();

    public DirectorPlanRevisionPromoter(ReviewOrchestrationService orchestrationService) {
        this.orchestrationService = Objects.requireNonNull(
                orchestrationService, "orchestrationService must not be null");
    }

    /**
     * Promotes the workspace plan document to the next public plan revision when its content
     * differs from the last promoted document. Returns the revision when one was created.
     *
     * @param context runtime identity of the review attempt
     * @param workspace attempt-layout workspace whose `plans/` directory is scanned
     */
    public Optional<ReviewOrchestrationService.PlanRevision> promoteIfChanged(
            ReviewRuntimeContext context, ReviewWorkspaceLayout.ReviewWorkspace workspace) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        Path planDocument = workspace.plans().resolve(PLAN_DOCUMENT_NAME);
        if (!Files.isRegularFile(planDocument)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(planDocument, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("director_plan_document_read_failed reviewId={} attemptNo={} error={}",
                    context.reviewId().value(), context.attemptNo(), exception.getMessage());
            return Optional.empty();
        }
        if (content.isBlank()) {
            LOGGER.info("director_plan_document_ignored reviewId={} attemptNo={} reason=blank",
                    context.reviewId().value(), context.attemptNo());
            return Optional.empty();
        }
        ParsedPlan parsed = ParsedPlan.parse(content);
        if (parsed.tasks().isEmpty()) {
            LOGGER.info("director_plan_document_ignored reviewId={} attemptNo={} reason=no-task-list",
                    context.reviewId().value(), context.attemptNo());
            return Optional.empty();
        }
        String digest = sha256(content);
        PromoterState state = states.computeIfAbsent(context.runtimeId(), ignored -> new PromoterState());
        synchronized (state) {
            if (digest.equals(state.lastPromotedDigest)) {
                return Optional.empty();
            }
            try {
                ReviewOrchestrationService.PlanRevision revision =
                        orchestrationService.revisePlan(context, workspace, parsed.tasks(), parsed.reason());
                state.lastPromotedDigest = digest;
                state.lastPromotedVersion = revision.plan().planVersion();
                LOGGER.info("director_plan_promoted reviewId={} attemptNo={} version={}",
                        context.reviewId().value(), context.attemptNo(), revision.plan().planVersion());
                return Optional.of(revision);
            } catch (RuntimeException exception) {
                // A refused revision must never break the running Director; the unchanged document
                // is marked consumed so it is not retried on every later wake.
                LOGGER.warn("director_plan_promote_rejected reviewId={} attemptNo={} error={}",
                        context.reviewId().value(), context.attemptNo(), exception.getMessage());
                state.lastPromotedDigest = digest;
                return Optional.empty();
            }
        }
    }

    /**
     * Returns the highest public plan version promoted for a runtime, or empty when nothing was
     * promoted yet. Exposed for observability and tests.
     */
    public OptionalInt lastPromotedVersion(String runtimeId) {
        Objects.requireNonNull(runtimeId, "runtimeId must not be null");
        PromoterState state = states.get(runtimeId);
        if (state == null || state.lastPromotedDigest == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(state.lastPromotedVersion);
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * View of a Director markdown plan: a non-empty task list plus an optional change reason.
     * List items drive `publicTasks`; explicit 修订原因/变更原因 lines drive the revision
     * reason so the plan card shows why the Director changed the plan.
     */
    record ParsedPlan(List<String> tasks, String reason) {

        static ParsedPlan parse(String content) {
            List<String> tasks = new ArrayList<>();
            String reason = null;
            for (String raw : content.split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (reason == null) {
                    Matcher reasonMatcher = REASON_LINE.matcher(line);
                    if (reasonMatcher.matches()) {
                        reason = clean(reasonMatcher.group(1));
                        continue;
                    }
                }
                Matcher itemMatcher = LIST_ITEM.matcher(line);
                if (itemMatcher.matches()) {
                    String task = clean(itemMatcher.group(1));
                    if (!task.isEmpty()) {
                        tasks.add(task);
                    }
                }
            }
            return new ParsedPlan(List.copyOf(tasks), reason == null ? DEFAULT_CHANGE_REASON : reason);
        }

        private static String clean(String value) {
            String text = value.strip();
            text = text.replaceAll("^\\*\\*+|\\*\\*+$", "").strip();
            text = text.replaceAll("^`+|`+$", "").strip();
            return text;
        }
    }

    /**
     * Per-runtime promotion watermark. Nullable digest means nothing from this workspace has been
     * promoted yet; version documents the highest promoted public version for observability.
     */
    private static final class PromoterState {

        private String lastPromotedDigest;
        private int lastPromotedVersion;
    }
}
