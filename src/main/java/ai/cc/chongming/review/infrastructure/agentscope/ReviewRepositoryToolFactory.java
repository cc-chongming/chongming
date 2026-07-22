package ai.cc.chongming.review.infrastructure.agentscope;

import ai.cc.chongming.review.application.RepositorySnapshotService;
import ai.cc.chongming.review.application.ReviewIntakeService;
import ai.cc.chongming.review.application.ReviewRuntimeContext;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.tool.ReadOnlyRepositoryTools;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryToolContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Registers only server-bound, snapshot-relative local repository reads for review roles.
 *
 * <p>The model never receives a host path or selects a repository. The requirement snapshot supplies the
 * administrator-configured repository identifier, and all reads are served from the resulting frozen copy.
 *
 * @author wangli
 */
@Component
public class ReviewRepositoryToolFactory {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LINE_COUNT = 80;
    private static final int MAX_LINE_COUNT = 200;

    private final ReviewIntakeService intakeService;
    private final RepositorySnapshotService snapshotService;
    private final ReadOnlyRepositoryTools repositoryTools;
    private final ConcurrentMap<String, RepositorySnapshot> snapshotsByReviewAttempt = new ConcurrentHashMap<>();

    public ReviewRepositoryToolFactory(
            ReviewIntakeService intakeService,
            RepositorySnapshotService snapshotService,
            ReadOnlyRepositoryTools repositoryTools) {
        this.intakeService = Objects.requireNonNull(intakeService, "intakeService must not be null");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService must not be null");
        this.repositoryTools = Objects.requireNonNull(repositoryTools, "repositoryTools must not be null");
    }

    /**
     * Builds only the read tools granted by the static RolePack for this role and review attempt.
     */
    public List<AgentTool> readTools(
            ReviewRuntimeContext runtimeContext, RoleType roleType, Set<String> allowedToolNames) {
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        Objects.requireNonNull(allowedToolNames, "allowedToolNames must not be null");
        if (roleType == RoleType.DIRECTOR || roleType == RoleType.JUDGE) {
            return List.of();
        }
        RepositoryToolContext context = toolContext(runtimeContext, roleType);
        List<AgentTool> tools = new ArrayList<>();
        if (allowedToolNames.contains("listFiles")) {
            tools.add(new ListFilesTool(context, runtimeContext.cancellation()));
        }
        if (allowedToolNames.contains("searchText")) {
            tools.add(new SearchTextTool(context, runtimeContext.cancellation()));
        }
        if (allowedToolNames.contains("findSymbol")) {
            tools.add(new FindSymbolTool(context, runtimeContext.cancellation()));
        }
        if (allowedToolNames.contains("readLines")) {
            tools.add(new ReadLinesTool(context, runtimeContext.cancellation()));
        }
        if (allowedToolNames.contains("getFileMetadata")) {
            tools.add(new FileMetadataTool(context, runtimeContext.cancellation()));
        }
        return List.copyOf(tools);
    }

    private RepositoryToolContext toolContext(ReviewRuntimeContext runtimeContext, RoleType roleType) {
        RequirementSnapshot requirementSnapshot = intakeService.requireSnapshot(
                runtimeContext.reviewId(), runtimeContext.attemptNo());
        String key = runtimeContext.reviewId().value() + ":" + runtimeContext.attemptNo();
        RepositorySnapshot repositorySnapshot = snapshotsByReviewAttempt.computeIfAbsent(
                key,
                ignored -> snapshotService.findExistingSnapshot(
                                runtimeContext.reviewId(), runtimeContext.attemptNo(), requirementSnapshot.repositoryPath())
                        .orElseGet(() -> snapshotService.bindSnapshot(
                                runtimeContext.reviewId(), runtimeContext.attemptNo(), requirementSnapshot.repositoryPath(),
                                requirementSnapshot.contentHash(), runtimeContext.cancellation())));
        return new RepositoryToolContext(
                runtimeContext.runtimeId(), runtimeContext.reviewId(), roleType, repositorySnapshot);
    }

    private abstract class BoundReadTool implements AgentTool {
        private final RepositoryToolContext context;
        private final IntakeCancellation cancellation;

        private BoundReadTool(RepositoryToolContext context, IntakeCancellation cancellation) {
            this.context = context;
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        }

        @Override
        public final Boolean getStrict() {
            return true;
        }

        final RepositoryToolContext context() {
            return context;
        }

        final IntakeCancellation cancellation() {
            return cancellation;
        }

        @Override
        public final Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromSupplier(() -> ToolResultBlock.text(String.valueOf(read(param.getInput()))))
                    .onErrorResume(exception -> Mono.just(ToolResultBlock.error("repository read rejected")));
        }

        abstract Object read(Map<String, Object> input);
    }

    private final class ListFilesTool extends BoundReadTool {
        private ListFilesTool(RepositoryToolContext context, IntakeCancellation cancellation) { super(context, cancellation); }
        @Override public String getName() { return "listFiles"; }
        @Override public String getDescription() { return "List text files from the server-frozen repository snapshot."; }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of("limit", integerSchema("Maximum files to return", 1, MAX_LIMIT)), List.of());
        }
        @Override Object read(Map<String, Object> input) {
            return repositoryTools.listFiles(context(), limit(input), cancellation());
        }
    }

    private final class SearchTextTool extends BoundReadTool {
        private SearchTextTool(RepositoryToolContext context, IntakeCancellation cancellation) { super(context, cancellation); }
        @Override public String getName() { return "searchText"; }
        @Override public String getDescription() { return "Search text only within the server-frozen repository snapshot."; }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of(
                    "query", stringSchema("Text or regular expression to search"),
                    "regularExpression", Map.of("type", "boolean", "description", "Treat query as a regular expression"),
                    "limit", integerSchema("Maximum matches to return", 1, MAX_LIMIT)), List.of("query"));
        }
        @Override Object read(Map<String, Object> input) {
            return repositoryTools.searchText(context(), requiredText(input, "query"), booleanValue(input, "regularExpression"),
                    limit(input), cancellation());
        }
    }

    private final class FindSymbolTool extends BoundReadTool {
        private FindSymbolTool(RepositoryToolContext context, IntakeCancellation cancellation) { super(context, cancellation); }
        @Override public String getName() { return "findSymbol"; }
        @Override public String getDescription() { return "Find lexical symbol candidates only within the server-frozen repository snapshot."; }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of(
                    "symbol", stringSchema("Symbol name to find"),
                    "limit", integerSchema("Maximum matches to return", 1, MAX_LIMIT)), List.of("symbol"));
        }
        @Override Object read(Map<String, Object> input) {
            return repositoryTools.findSymbol(context(), requiredText(input, "symbol"), limit(input), cancellation());
        }
    }

    private final class ReadLinesTool extends BoundReadTool {
        private ReadLinesTool(RepositoryToolContext context, IntakeCancellation cancellation) { super(context, cancellation); }
        @Override public String getName() { return "readLines"; }
        @Override public String getDescription() { return "Read a bounded line range from one snapshot-relative source file."; }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of(
                    "relativePath", stringSchema("Path relative to the frozen repository root"),
                    "startLine", integerSchema("First one-based line number", 1, Integer.MAX_VALUE),
                    "lineCount", integerSchema("Maximum lines to return", 1, MAX_LINE_COUNT)), List.of("relativePath", "startLine"));
        }
        @Override Object read(Map<String, Object> input) {
            return repositoryTools.readLines(context(), requiredText(input, "relativePath"),
                    integer(input, "startLine", 1, Integer.MAX_VALUE),
                    integer(input, "lineCount", DEFAULT_LINE_COUNT, MAX_LINE_COUNT), cancellation());
        }
    }

    private final class FileMetadataTool extends BoundReadTool {
        private FileMetadataTool(RepositoryToolContext context, IntakeCancellation cancellation) { super(context, cancellation); }
        @Override public String getName() { return "getFileMetadata"; }
        @Override public String getDescription() { return "Return metadata for one snapshot-relative source file."; }
        @Override public Map<String, Object> getParameters() {
            return objectSchema(Map.of("relativePath", stringSchema("Path relative to the frozen repository root")),
                    List.of("relativePath"));
        }
        @Override Object read(Map<String, Object> input) {
            return repositoryTools.getFileMetadata(context(), requiredText(input, "relativePath"), cancellation());
        }
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
