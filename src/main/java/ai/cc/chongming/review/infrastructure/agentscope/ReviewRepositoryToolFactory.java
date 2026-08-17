package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.RepositorySnapshotService;
import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.application.ReviewIntakeService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.ReviewContextAssembler;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.tool.ReadOnlyRepositoryTools;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrant;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrantSet;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryToolContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * [AIREVIEW-PLAN-023#5] Registers only server-bound, snapshot-relative local repository reads for review roles.
 *
 * <p>[AIREVIEW-PLAN-024] Role agents no longer submit or receive repository paths. Each role is
 * issued an unguessable {@code fileRef} grant set computed from
 * {@code snapshotFiles ∩ rolePathPolicy ∩ reviewRelevantFiles}; {@code readLines} and
 * {@code getFileMetadata} accept only {@code fileRef}, listing/search results expose only granted
 * fileRefs, rejected calls never consume read budget, and identical rejected calls are
 * short-circuited at the role-run level. When a role has no granted file, the read tools are not
 * registered at all and the role is instructed to mark affected checkpoints UNKNOWN.
 *
 * @author zyj
 */
@Component
public class ReviewRepositoryToolFactory {

    private static final Logger log = LoggerFactory.getLogger(ReviewRepositoryToolFactory.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LINE_COUNT = 80;
    private static final int MAX_LINE_COUNT = 200;
    private static final int INITIAL_REVIEW_READ_TOOL_CALLS = 36;

    private final ReviewIntakeService intakeService;
    private final RepositorySnapshotService snapshotService;
    private final ReadOnlyRepositoryTools repositoryTools;
    private final ReviewContextAssembler contextAssembler;
    private final ConcurrentMap<String, RepositorySnapshot> snapshotsByReviewAttempt = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SharedProjectContext> sharedContextsByReviewAttempt = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> scoutResultsByReviewAttempt = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<String>> snapshotFilesByReviewAttempt = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RepositoryFileGrantSet> grantSetsByReviewAttemptRole = new ConcurrentHashMap<>();

    public ReviewRepositoryToolFactory(
            ReviewIntakeService intakeService,
            RepositorySnapshotService snapshotService,
            ReadOnlyRepositoryTools repositoryTools) {
        this(intakeService, snapshotService, repositoryTools, null);
    }

    @Autowired
    public ReviewRepositoryToolFactory(
            ReviewIntakeService intakeService,
            RepositorySnapshotService snapshotService,
            ReadOnlyRepositoryTools repositoryTools,
            ReviewContextAssembler contextAssembler) {
        this.intakeService = Objects.requireNonNull(intakeService, "intakeService must not be null");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService must not be null");
        this.repositoryTools = Objects.requireNonNull(repositoryTools, "repositoryTools must not be null");
        this.contextAssembler = contextAssembler;
    }

    /**
     * Builds only the read tools granted by the static RolePack for this role and review attempt.
     */
    public List<AgentTool> readTools(
            ReviewRuntimeContext runtimeContext, RoleType roleType, Set<String> allowedToolNames) {
        return readTools(runtimeContext, roleType, allowedToolNames, null);
    }

    /**
     * [AIREVIEW-PLAN-024] Builds the role read tools against an explicit grant set, or against the
     * server-computed grant set when none is supplied. {@code readLines}/{@code getFileMetadata}
     * are not registered when the effective grant set is empty.
     */
    public List<AgentTool> readTools(
            ReviewRuntimeContext runtimeContext,
            RoleType roleType,
            Set<String> allowedToolNames,
            RepositoryFileGrantSet grantedFiles) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        Objects.requireNonNull(allowedToolNames, "allowedToolNames must not be null");
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE) {
            return List.of();
        }
        RepositoryToolContext context = toolContext(runtimeContext, roleType);
        RepositoryFileGrantSet grants = grantedFiles != null
                ? grantedFiles.boundTo(runtimeContext.reviewId(), runtimeContext.attemptNo(), roleType)
                : roleFileGrants(runtimeContext, roleType, context);
        RepositoryReadBudget readBudget = allowedToolNames.contains("complete_initial_review")
                ? new RepositoryReadBudget(INITIAL_REVIEW_READ_TOOL_CALLS)
                : null;
        ConcurrentMap<String, String> rejectedCalls = new ConcurrentHashMap<>();
        List<AgentTool> tools = new ArrayList<>();
        if (allowedToolNames.contains("listFiles")) {
            tools.add(new ListFilesTool(context, runtimeContext.cancellation(), readBudget, grants, rejectedCalls));
        }
        if (allowedToolNames.contains("searchText")) {
            tools.add(new SearchTextTool(context, runtimeContext.cancellation(), readBudget, grants, rejectedCalls));
        }
        if (allowedToolNames.contains("findSymbol")) {
            tools.add(new FindSymbolTool(context, runtimeContext.cancellation(), readBudget, grants, rejectedCalls));
        }
        if (grants.isEmpty()) {
            if (allowedToolNames.contains("readLines") || allowedToolNames.contains("getFileMetadata")) {
                log.info("Review role {} has no granted repository files; readLines/getFileMetadata are not registered"
                        + " and affected checkpoints must be submitted as UNKNOWN", roleType);
            }
        } else {
            if (allowedToolNames.contains("readLines")) {
                tools.add(new ReadLinesTool(context, runtimeContext.cancellation(), readBudget, grants, rejectedCalls));
            }
            if (allowedToolNames.contains("getFileMetadata")) {
                tools.add(new FileMetadataTool(context, runtimeContext.cancellation(), readBudget, grants, rejectedCalls));
            }
        }
        return List.copyOf(tools);
    }

    /**
     * [AIREVIEW-PLAN-024] Returns the role's effective fileRef grant set, computed once per review
     * attempt and role as {@code snapshotFiles ∩ rolePathPolicy ∩ reviewRelevantFiles}.
     */
    public RepositoryFileGrantSet roleFileGrants(ReviewRuntimeContext runtimeContext, RoleType roleType) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE) {
            return RepositoryFileGrantSet.empty();
        }
        return roleFileGrants(runtimeContext, roleType, toolContext(runtimeContext, roleType));
    }

    /**
     * Resolves the frozen shared snapshot used as the lower layer of the Context Scout's native
     * AgentScope filesystem. Physical paths remain inside server-side Harness construction.
     */
    public RepositorySnapshot requireSnapshot(ReviewRuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        return toolContext(runtimeContext, RoleType.DIRECTOR, Set.of("")).snapshot();
    }

    /**
     * Produces one bounded, server-derived public project overview for all roles in a review attempt.
     * The output contains no host paths and is not a replacement for role-specific repository reads.
     */
    public SharedProjectContext sharedProjectContext(ReviewRuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        String key = runtimeContext.reviewId().value() + ":" + runtimeContext.attemptNo();
        return sharedContextsByReviewAttempt.computeIfAbsent(key, ignored -> buildSharedProjectContext(runtimeContext));
    }

    /**
     * Assembles the role's bounded public context from selector-addressable snapshot and Scout facts.
     * [AIREVIEW-PLAN-024] Candidate files are rendered only as granted fileRefs; ungranted paths
     * never appear in the role context.
     */
    public String rolePublicContext(
            ReviewRuntimeContext runtimeContext,
            ai.cc.chongming.review.domain.role.RolePack rolePack,
            ReviewContextAssembler contextAssembler) {
        Objects.requireNonNull(rolePack, "rolePack must not be null");
        Objects.requireNonNull(contextAssembler, "contextAssembler must not be null");
        SharedProjectContext overview = sharedProjectContext(runtimeContext);
        RepositoryFileGrantSet grants = roleFileGrants(runtimeContext, rolePack.roleType());
        Instant createdAt = Instant.now();
        List<ReviewContextAssembler.ContextFact> facts = new ArrayList<>(List.of(
                new ReviewContextAssembler.ContextFact(
                        "requirement-snapshot", "requirement-snapshot", ReviewContextAssembler.Priority.CRITICAL,
                        false, createdAt, "Public requirement context:\n" + String.join("\n", overview.requirementSections())),
                new ReviewContextAssembler.ContextFact(
                        "repository-snapshot", "repository-snapshot", ReviewContextAssembler.Priority.HIGH,
                        false, createdAt, repositorySummary(overview, rolePack.roleType())),
                new ReviewContextAssembler.ContextFact(
                        "role-scope", "role-scope", ReviewContextAssembler.Priority.CRITICAL,
                        false, createdAt, roleScopeFactText(rolePack.roleType(), grants))));
        facts.add(contextAssembler.contextScoutFact(
                        runtimeContext.reviewId(), runtimeContext.attemptNo(), rolePack.roleType())
                .orElseGet(() -> new ReviewContextAssembler.ContextFact(
                        "scout-overview", "scout-overview", ReviewContextAssembler.Priority.HIGH,
                        false, createdAt,
                        "Context Scout overview: " + scoutSummary(runtimeContext, overview, rolePack.roleType()))));
        ReviewContextAssembler.AssembledContext assembled = contextAssembler.assemble(
                new ReviewContextAssembler.ContextRequest(runtimeContext.reviewId(), rolePack, facts, 8_000));
        return assembled.facts().stream()
                .map(ReviewContextAssembler.ContextFact::publicText)
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    /** Releases only one cancelled attempt's process-local cache entries. */
    public void release(ReviewRuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        String key = runtimeContext.reviewId().value() + ":" + runtimeContext.attemptNo();
        snapshotsByReviewAttempt.remove(key);
        sharedContextsByReviewAttempt.remove(key);
        scoutResultsByReviewAttempt.remove(key);
        snapshotFilesByReviewAttempt.remove(key);
        grantSetsByReviewAttemptRole.keySet().removeIf(roleKey -> roleKey.startsWith(key + ":"));
    }

    /** Stores only the Scout's final visible response; hidden reasoning is never captured. */
    public void recordScoutResult(ReviewRuntimeContext runtimeContext, String visibleResult) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        if (visibleResult == null || visibleResult.isBlank()) {
            return;
        }
        String key = runtimeContext.reviewId().value() + ":" + runtimeContext.attemptNo();
        scoutResultsByReviewAttempt.put(key, abbreviate(visibleResult, 6_000));
    }

    private RepositoryToolContext toolContext(ReviewRuntimeContext runtimeContext, RoleType roleType) {
        return toolContext(runtimeContext, roleType, allowedPathPrefixes(roleType));
    }

    private RepositoryToolContext toolContext(
            ReviewRuntimeContext runtimeContext, RoleType roleType, Set<String> allowedPathPrefixes) {
        RequirementSnapshot requirementSnapshot = intakeService.requireSnapshot(
                runtimeContext.reviewId(), runtimeContext.attemptNo());
        String key = runtimeContext.reviewId().value() + ":" + runtimeContext.attemptNo();
        // [AIREVIEW-PLAN-029] Snapshot resolution follows the intake repository source so online
        // repositories bind through the ad-hoc mirror engine like configured ones.
        ai.cc.chongming.review.application.RepositorySource repositorySource =
                ai.cc.chongming.review.application.RepositorySource.from(requirementSnapshot);
        RepositorySnapshot repositorySnapshot = snapshotsByReviewAttempt.computeIfAbsent(
                key,
                ignored -> snapshotService.findExistingSnapshot(
                                runtimeContext.reviewId(), runtimeContext.attemptNo(), repositorySource)
                        .orElseGet(() -> snapshotService.bindSnapshot(
                                runtimeContext.reviewId(), runtimeContext.attemptNo(), repositorySource,
                                requirementSnapshot.contentHash(), runtimeContext.cancellation())));
        return new RepositoryToolContext(
                runtimeContext.runtimeId(), runtimeContext.reviewId(), roleType, repositorySnapshot, allowedPathPrefixes);
    }

    /**
     * [AIREVIEW-PLAN-024] Computes and caches the role grant set in one pass over the cached
     * snapshot file list; no per-file lookup is performed.
     */
    private RepositoryFileGrantSet roleFileGrants(
            ReviewRuntimeContext runtimeContext, RoleType roleType, RepositoryToolContext context) {
        String key = runtimeContext.reviewId().value() + ":" + runtimeContext.attemptNo() + ":" + roleType.name();
        return grantSetsByReviewAttemptRole.computeIfAbsent(key, ignored -> {
            Set<String> prefixes = allowedPathPrefixes(roleType);
            Predicate<String> rolePathPolicy = path -> RepositoryToolContext.allows(prefixes, path);
            List<String> snapshotFiles = snapshotFileList(runtimeContext, context);
            Set<String> effective;
            if (contextAssembler != null) {
                Predicate<String> reviewRelevance = contextAssembler.reviewRelevancePredicate(
                        runtimeContext.reviewId(), runtimeContext.attemptNo(), roleType);
                effective = contextAssembler.effectiveReadableFiles(snapshotFiles, rolePathPolicy, reviewRelevance);
                return contextAssembler.fileGrants(
                        runtimeContext.reviewId(), runtimeContext.attemptNo(), roleType,
                        context.snapshot().headCommit(), effective);
            }
            effective = new LinkedHashSet<>();
            for (String file : snapshotFiles) {
                if (file == null || file.isBlank()) {
                    continue;
                }
                String normalized = file.replace('\\', '/');
                if (rolePathPolicy.test(normalized)) {
                    effective.add(normalized);
                }
            }
            List<RepositoryFileGrant> grants = new ArrayList<>();
            for (String file : effective) {
                grants.add(RepositoryFileGrant.issue(
                        runtimeContext.reviewId(), runtimeContext.attemptNo(), roleType,
                        context.snapshot().headCommit(), file));
            }
            return RepositoryFileGrantSet.of(grants);
        });
    }

    private List<String> snapshotFileList(ReviewRuntimeContext runtimeContext, RepositoryToolContext context) {
        String key = runtimeContext.reviewId().value() + ":" + runtimeContext.attemptNo();
        return snapshotFilesByReviewAttempt.computeIfAbsent(
                key, ignored -> repositoryTools.snapshotFiles(context, runtimeContext.cancellation()));
    }

    private SharedProjectContext buildSharedProjectContext(ReviewRuntimeContext runtimeContext) {
        RequirementSnapshot requirement = intakeService.requireSnapshot(runtimeContext.reviewId(), runtimeContext.attemptNo());
        RepositoryToolContext toolContext = toolContext(runtimeContext, RoleType.PROJECT, Set.of(""));
        RepositorySnapshot snapshot = toolContext.snapshot();
        if (snapshot.includedFileCount() == 0) {
            throw new RepositoryAccessException(
                    RepositoryAccessException.Code.SNAPSHOT_FAILED,
                    "The repository snapshot contains no reviewable text files");
        }
        List<ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.FileMetadata> files =
                repositoryTools.listFiles(toolContext, 40, runtimeContext.cancellation());
        Set<String> moduleRoots = new LinkedHashSet<>();
        for (var file : files) {
            int separator = file.relativePath().indexOf('/');
            moduleRoots.add(separator < 0 ? "." : file.relativePath().substring(0, separator));
        }
        List<String> requirementSections = requirement.document().sections().stream()
                .limit(16)
                .map(section -> "- " + section.heading() + ": " + abbreviate(section.content(), 600))
                .toList();
        return new SharedProjectContext(
                requirement.repositoryPath(),
                snapshot.headCommit(),
                snapshot.branch(),
                snapshot.includedFileCount(),
                requirementSections,
                List.copyOf(moduleRoots),
                files.stream().map(file -> file.relativePath()).toList());
    }

    private static String abbreviate(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "(empty)";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    /**
     * [AIREVIEW-PLAN-024] The summary no longer renders sample file paths, because they were
     * assembled from a repository-wide listing and could expose files outside a role's grants.
     */
    private static String repositorySummary(SharedProjectContext overview, RoleType roleType) {
        String summary = "Repository overview: repository=" + overview.repositoryId() + ", branch=" + overview.branch()
                + ", commit=" + overview.headCommit() + ", reviewableFiles=" + overview.includedFileCount() + ".";
        if (roleType == RoleType.PRODUCT) {
            return summary;
        }
        return summary + " Module roots: " + String.join(", ", overview.moduleRoots()) + ".";
    }

    /**
     * [AIREVIEW-PLAN-024] Renders the role scope either as its granted fileRef list or as an
     * explicit UNKNOWN instruction when no repository file is granted.
     */
    private static String roleScopeFactText(RoleType roleType, RepositoryFileGrantSet grants) {
        if (grants.isEmpty()) {
            return "No repository file is granted to " + roleType.name() + " for this review snapshot. "
                    + "Mark every checkpoint that requires repository evidence as UNKNOWN and state which evidence is missing. "
                    + "Do not guess file paths, do not fabricate fileRefs, and do not retry rejected repository calls.";
        }
        return "Authorized repository files for " + roleType.name() + " (" + grants.size()
                + " files). readLines and getFileMetadata accept only these server-issued fileRef values; "
                + "never submit a file path: "
                + grants.grants().stream()
                        .map(grant -> grant.fileRef() + " (" + fileNameOf(grant.normalizedPath()) + ")")
                        .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String fileNameOf(String normalizedPath) {
        int separator = normalizedPath.lastIndexOf('/');
        return separator < 0 ? normalizedPath : normalizedPath.substring(separator + 1);
    }

    private String scoutSummary(ReviewRuntimeContext runtimeContext, SharedProjectContext overview, RoleType roleType) {
        String key = runtimeContext.reviewId().value() + ":" + runtimeContext.attemptNo();
        String result = scoutResultsByReviewAttempt.get(key);
        if (result != null) {
            return result;
        }
        if (roleType == RoleType.PRODUCT) {
            return "The requirement and repository metadata are ready; use targeted evidence only when needed.";
        }
        return "The frozen snapshot contains module roots " + String.join(", ", overview.moduleRoots())
                + "; use only the granted fileRefs for targeted reads.";
    }

    private static Set<String> allowedPathPrefixes(RoleType roleType) {
        return switch (roleType) {
            case PRODUCT -> Set.of("README.md", "docs/");
            case PROJECT -> Set.of("README.md", "docs/", "pom.xml", "package.json", "build.gradle", "settings.gradle");
            case FRONTEND -> Set.of("frontend/", "web/", "ui/", "client/");
            case BACKEND -> Set.of("src/main/", "src/test/", "backend/", "server/", "service/", "api/");
            case TESTING -> Set.of("src/test/", "test/", "tests/");
            case SECURITY, ARCHITECTURE, DIRECTOR, JUDGE, PERFORMANCE -> Set.of("");
        };
    }

    private abstract class BoundReadTool implements AgentTool {
        private final RepositoryToolContext context;
        private final IntakeCancellation cancellation;
        private final RepositoryReadBudget readBudget;
        private final RepositoryFileGrantSet grants;
        private final ConcurrentMap<String, String> rejectedCalls;

        private BoundReadTool(
                RepositoryToolContext context,
                IntakeCancellation cancellation,
                RepositoryReadBudget readBudget,
                RepositoryFileGrantSet grants,
                ConcurrentMap<String, String> rejectedCalls) {
            this.context = context;
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
            this.readBudget = readBudget;
            this.grants = Objects.requireNonNull(grants, "grants must not be null");
            this.rejectedCalls = Objects.requireNonNull(rejectedCalls, "rejectedCalls must not be null");
        }

        @Override
        public final Boolean getStrict() {
            return true;
        }

        @Override
        public final boolean isReadOnly() {
            return true;
        }

        final RepositoryToolContext context() {
            return context;
        }

        final IntakeCancellation cancellation() {
            return cancellation;
        }

        final RepositoryFileGrantSet grants() {
            return grants;
        }

        /**
         * [AIREVIEW-PLAN-024] Validation order is parameter shape → fileRef authorization/snapshot
         * ownership → read-budget deduction → actual read. Rejections, out-of-scope requests and
         * missing files never consume budget, and an identical rejected call is short-circuited at
         * the role-run level without touching the underlying repository again.
         */
        @Override
        public final Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromSupplier(() -> {
                Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
                String shortCircuitKey = getName() + '\u0000' + new TreeMap<>(input);
                String replayedError = rejectedCalls.get(shortCircuitKey);
                if (replayedError != null) {
                    log.warn("Review repository tool short-circuited: tool={}, role={}, error={}",
                            getName(), context.roleType(), replayedError);
                    return ToolResultBlock.error(replayedError);
                }
                try {
                    return ToolResultBlock.text(String.valueOf(read(input)));
                } catch (RuntimeException exception) {
                    String error = "REPOSITORY_TOOL_ERROR:" + toolErrorCode(exception);
                    rejectedCalls.putIfAbsent(shortCircuitKey, error);
                    log.warn("Review repository tool rejected: tool={}, role={}, error={}",
                            getName(), context.roleType(), error);
                    return ToolResultBlock.error(error);
                }
            });
        }

        /** Consumes budget only after parameter and authorization checks have passed. */
        final void consumeReadBudget() {
            if (readBudget != null && !readBudget.tryConsume()) {
                throw new RepositoryAccessException(
                        RepositoryAccessException.Code.READ_BUDGET_EXHAUSTED,
                        "Repository read budget is exhausted");
            }
        }

        /** Resolves the granted fileRef, enforcing role grant and snapshot ownership. */
        final RepositoryFileGrant requireGrantedFile(Map<String, Object> input) {
            String fileRef = requiredText(input, "fileRef");
            RepositoryFileGrant grant = grants.resolve(fileRef)
                    .orElseThrow(() -> new RepositoryAccessException(
                            RepositoryAccessException.Code.FILE_REF_NOT_GRANTED,
                            "fileRef is not granted to this role"));
            if (!grant.snapshotCommit().equals(context.snapshot().headCommit())) {
                throw new RepositoryAccessException(
                        RepositoryAccessException.Code.FILE_NOT_IN_SNAPSHOT,
                        "fileRef does not belong to the current review snapshot");
            }
            return grant;
        }

        /** Maps a scope-filtered snapshot path back to its issued fileRef. */
        final String grantedFileRef(String normalizedPath) {
            return grants.fileRefFor(normalizedPath).orElseThrow(
                    () -> new IllegalStateException("Grant missing for scope-filtered snapshot file"));
        }

        abstract Object read(Map<String, Object> input);
    }

    private static String toolErrorCode(Throwable exception) {
        if (exception instanceof RepositoryAccessException accessException) {
            return switch (accessException.code()) {
                case FILE_REF_NOT_GRANTED -> "FILE_REF_NOT_GRANTED: this fileRef is not granted to your role;"
                        + " do-not-retry; use another granted fileRef or mark the checkpoint UNKNOWN";
                case FILE_NOT_IN_SNAPSHOT -> "FILE_NOT_IN_SNAPSHOT: this fileRef does not belong to the current"
                        + " review snapshot; do-not-retry; mark the checkpoint UNKNOWN";
                case INVALID_LINE_RANGE -> "INVALID_LINE_RANGE: the requested line range is invalid;"
                        + " do-not-retry with the same range";
                case READ_BUDGET_EXHAUSTED, REPOSITORY_READ_BUDGET_EXHAUSTED ->
                    "READ_BUDGET_EXHAUSTED: stop repository reads and submit existing findings";
                default -> accessException.code().name();
            };
        }
        if (exception instanceof IllegalArgumentException) {
            return "INVALID_ARGUMENT";
        }
        return "SNAPSHOT_UNAVAILABLE";
    }

    /**
     * Server-generated, bounded context shared by roles without revealing physical repository paths.
     *
     * <p>[AIREVIEW-PLAN-024] The public text no longer renders sample file paths; roles receive
     * only their own granted fileRefs through the role-scope fact.
     *
     * @author wangli
     */
    public record SharedProjectContext(
            String repositoryId,
            String headCommit,
            String branch,
            long includedFileCount,
            List<String> requirementSections,
            List<String> moduleRoots,
            List<String> sampleFiles) {

        public SharedProjectContext {
            requirementSections = List.copyOf(requirementSections);
            moduleRoots = List.copyOf(moduleRoots);
            sampleFiles = List.copyOf(sampleFiles);
        }

        public String publicText(RoleType roleType) {
            String requirement = String.join("\n", requirementSections);
            String overview = "Repository overview: repository=" + repositoryId + ", branch=" + branch
                    + ", commit=" + headCommit + ", reviewableFiles=" + includedFileCount + ".";
            if (roleType == RoleType.PRODUCT) {
                return "Public requirement context:\n" + requirement + "\n" + overview;
            }
            return "Public requirement context:\n" + requirement + "\n" + overview
                    + "\nModule roots: " + String.join(", ", moduleRoots);
        }
    }

    /**
     * [AIREVIEW-PLAN-024] File-listing result exposing only granted fileRefs, never paths.
     *
     * @author wangli
     */
    public record GrantedFileListing(String fileRef, String fileName, String language, long size) {
    }

    /**
     * [AIREVIEW-PLAN-024] Search result exposing only granted fileRefs, never paths.
     *
     * @author wangli
     */
    public record GrantedTextMatch(String fileRef, int lineNumber, String line) {
    }

    /**
     * [AIREVIEW-PLAN-024] File metadata result exposing only the granted fileRef, never the path.
     *
     * @author wangli
     */
    public record GrantedFileMetadata(
            String fileRef, String fileName, String language, long size, String fileHash, Instant lastModifiedAt) {
    }

    private final class ListFilesTool extends BoundReadTool {
        private ListFilesTool(
                RepositoryToolContext context, IntakeCancellation cancellation, RepositoryReadBudget readBudget,
                RepositoryFileGrantSet grants, ConcurrentMap<String, String> rejectedCalls) {
            super(context, cancellation, readBudget, grants, rejectedCalls);
        }
        @Override public String getName() { return "listFiles"; }
        @Override public String getDescription() {
            return "List granted text files of the server-frozen repository snapshot as fileRefs.";
        }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of("limit", integerSchema("Maximum files to return", 1, MAX_LIMIT)), List.of());
        }
        @Override Object read(Map<String, Object> input) {
            int effectiveLimit = limit(input);
            consumeReadBudget();
            return repositoryTools.listFiles(context(), effectiveLimit, cancellation(), grants()::containsPath).stream()
                    .map(file -> new GrantedFileListing(
                            grantedFileRef(file.relativePath()), fileNameOf(file.relativePath()),
                            file.language(), file.size()))
                    .toList();
        }
    }

    private final class SearchTextTool extends BoundReadTool {
        private SearchTextTool(
                RepositoryToolContext context, IntakeCancellation cancellation, RepositoryReadBudget readBudget,
                RepositoryFileGrantSet grants, ConcurrentMap<String, String> rejectedCalls) {
            super(context, cancellation, readBudget, grants, rejectedCalls);
        }
        @Override public String getName() { return "searchText"; }
        @Override public String getDescription() {
            return "Search text within the granted files of the server-frozen repository snapshot; matches reference fileRefs.";
        }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of(
                    "query", stringSchema("Text or regular expression to search"),
                    "regularExpression", Map.of("type", "boolean", "description", "Treat query as a regular expression"),
                    "limit", integerSchema("Maximum matches to return", 1, MAX_LIMIT)), List.of("query"));
        }
        @Override Object read(Map<String, Object> input) {
            consumeReadBudget();
            return repositoryTools.searchText(
                            context(), requiredText(input, "query"), booleanValue(input, "regularExpression"),
                            limit(input), cancellation(), grants()::containsPath)
                    .stream()
                    .map(match -> new GrantedTextMatch(
                            grantedFileRef(match.relativePath()), match.lineNumber(), match.line()))
                    .toList();
        }
    }

    private final class FindSymbolTool extends BoundReadTool {
        private FindSymbolTool(
                RepositoryToolContext context, IntakeCancellation cancellation, RepositoryReadBudget readBudget,
                RepositoryFileGrantSet grants, ConcurrentMap<String, String> rejectedCalls) {
            super(context, cancellation, readBudget, grants, rejectedCalls);
        }
        @Override public String getName() { return "findSymbol"; }
        @Override public String getDescription() {
            return "Find lexical symbol candidates within the granted files of the snapshot; matches reference fileRefs.";
        }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of(
                    "symbol", stringSchema("Symbol name to find"),
                    "limit", integerSchema("Maximum matches to return", 1, MAX_LIMIT)), List.of("symbol"));
        }
        @Override Object read(Map<String, Object> input) {
            consumeReadBudget();
            return repositoryTools.findSymbol(
                            context(), requiredText(input, "symbol"), limit(input), cancellation(), grants()::containsPath)
                    .stream()
                    .map(match -> new GrantedTextMatch(
                            grantedFileRef(match.relativePath()), match.lineNumber(), match.line()))
                    .toList();
        }
    }

    private final class ReadLinesTool extends BoundReadTool {
        private ReadLinesTool(
                RepositoryToolContext context, IntakeCancellation cancellation, RepositoryReadBudget readBudget,
                RepositoryFileGrantSet grants, ConcurrentMap<String, String> rejectedCalls) {
            super(context, cancellation, readBudget, grants, rejectedCalls);
        }
        @Override public String getName() { return "readLines"; }
        @Override public String getDescription() {
            return "Read a bounded line range from one granted snapshot file addressed by fileRef; paths are never accepted.";
        }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of(
                    "fileRef", stringSchema("Server-issued fileRef of a granted snapshot file"),
                    "startLine", integerSchema("First one-based line number", 1, Integer.MAX_VALUE),
                    "lineCount", integerSchema("Maximum lines to return", 1, MAX_LINE_COUNT)), List.of("fileRef", "startLine"));
        }
        @Override Object read(Map<String, Object> input) {
            RepositoryFileGrant grant = requireGrantedFile(input);
            int startLine;
            int lineCount;
            try {
                startLine = integer(input, "startLine", 1, Integer.MAX_VALUE);
                lineCount = integer(input, "lineCount", DEFAULT_LINE_COUNT, MAX_LINE_COUNT);
            } catch (IllegalArgumentException exception) {
                throw new RepositoryAccessException(
                        RepositoryAccessException.Code.INVALID_LINE_RANGE, exception.getMessage());
            }
            consumeReadBudget();
            try {
                return repositoryTools.readLines(
                        context(), grant.normalizedPath(), startLine, lineCount, cancellation());
            } catch (RepositoryAccessException exception) {
                throw mapUnderlyingReadError(exception);
            } catch (IllegalArgumentException exception) {
                throw new RepositoryAccessException(
                        RepositoryAccessException.Code.FILE_REF_NOT_GRANTED, exception.getMessage());
            }
        }
    }

    private final class FileMetadataTool extends BoundReadTool {
        private FileMetadataTool(
                RepositoryToolContext context, IntakeCancellation cancellation, RepositoryReadBudget readBudget,
                RepositoryFileGrantSet grants, ConcurrentMap<String, String> rejectedCalls) {
            super(context, cancellation, readBudget, grants, rejectedCalls);
        }
        @Override public String getName() { return "getFileMetadata"; }
        @Override public String getDescription() {
            return "Return metadata for one granted snapshot file addressed by fileRef; paths are never accepted.";
        }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of(
                    "fileRef", stringSchema("Server-issued fileRef of a granted snapshot file")), List.of("fileRef"));
        }
        @Override Object read(Map<String, Object> input) {
            RepositoryFileGrant grant = requireGrantedFile(input);
            consumeReadBudget();
            var metadata = repositoryTools.getFileMetadata(context(), grant.normalizedPath(), cancellation());
            return new GrantedFileMetadata(
                    grantedFileRef(metadata.relativePath()), fileNameOf(metadata.relativePath()),
                    metadata.language(), metadata.size(), metadata.fileHash(), metadata.lastModifiedAt());
        }
    }

    private static RepositoryAccessException mapUnderlyingReadError(RepositoryAccessException exception) {
        if (exception.code() == RepositoryAccessException.Code.REPOSITORY_PATH_UNSAFE) {
            return new RepositoryAccessException(
                    RepositoryAccessException.Code.FILE_NOT_IN_SNAPSHOT,
                    "Granted file is not readable in the current review snapshot", exception);
        }
        return exception;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerSchema(String description, int minimum, int maximum) {
        return Map.of("type", "integer", "description", description, "minimum", minimum, "maximum", maximum);
    }

    private static int limit(Map<String, Object> input) {
        return integer(input, "limit", DEFAULT_LIMIT, MAX_LIMIT);
    }

    private static int integer(Map<String, Object> input, String field, int defaultValue, int maximum) {
        Object value = input.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int result = number.intValue();
        if (result < 1 || result > maximum) {
            throw new IllegalArgumentException(field + " is outside the permitted range");
        }
        return result;
    }

    private static boolean booleanValue(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return result;
    }

    private static String requiredText(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (!(value instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return result;
    }
}
