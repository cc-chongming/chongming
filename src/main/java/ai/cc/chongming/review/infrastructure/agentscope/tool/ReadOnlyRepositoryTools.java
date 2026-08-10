package ai.cc.chongming.review.infrastructure.agentscope.tool;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.FileMetadata;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.SourceLine;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.TextMatch;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Server-side facade for the repository tools exposed to AgentScope role agents.
 *
 * <p>[AIREVIEW-PLAN-024] Model-facing access is fileRef-based and resolved by
 * {@code ReviewRepositoryToolFactory}; the path-accepting methods here remain the server-side
 * defense-in-depth layer and additionally serve grant computation and evidence verification.
 *
 * @author wangli
 */
@Component
public class ReadOnlyRepositoryTools {

    private final RepositorySearchIndex searchIndex;

    public ReadOnlyRepositoryTools(RepositorySearchIndex searchIndex) {
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex must not be null");
    }

    /**
     * Lists only files already frozen beneath the caller's review snapshot.
     */
    public List<FileMetadata> listFiles(RepositoryToolContext context, int limit, IntakeCancellation cancellation) {
        return listFiles(context, limit, cancellation, ignored -> true);
    }

    /**
     * [AIREVIEW-PLAN-024] Lists granted files only; the grant scope narrows the role prefix policy.
     */
    public List<FileMetadata> listFiles(
            RepositoryToolContext context, int limit, IntakeCancellation cancellation, Predicate<String> grantScope) {
        RepositoryToolContext safeContext = requireContext(context);
        return searchIndex.listFiles(
                safeContext.snapshot(), limit, requireCancellation(cancellation), combinedScope(safeContext, grantScope));
    }

    /**
     * Searches only text copied into the caller's review snapshot.
     */
    public List<TextMatch> searchText(
            RepositoryToolContext context,
            String query,
            boolean regularExpression,
            int limit,
            IntakeCancellation cancellation) {
        return searchText(context, query, regularExpression, limit, cancellation, ignored -> true);
    }

    /**
     * [AIREVIEW-PLAN-024] Searches granted files only; the grant scope narrows the role prefix policy.
     */
    public List<TextMatch> searchText(
            RepositoryToolContext context,
            String query,
            boolean regularExpression,
            int limit,
            IntakeCancellation cancellation,
            Predicate<String> grantScope) {
        RepositoryToolContext safeContext = requireContext(context);
        return searchIndex.searchText(
                safeContext.snapshot(), query, regularExpression, limit, requireCancellation(cancellation),
                combinedScope(safeContext, grantScope));
    }

    /**
     * Finds lexical symbol candidates in the caller's frozen snapshot without invoking a compiler.
     */
    public List<TextMatch> findSymbol(
            RepositoryToolContext context, String symbol, int limit, IntakeCancellation cancellation) {
        return findSymbol(context, symbol, limit, cancellation, ignored -> true);
    }

    /**
     * [AIREVIEW-PLAN-024] Finds symbol candidates in granted files only.
     */
    public List<TextMatch> findSymbol(
            RepositoryToolContext context,
            String symbol,
            int limit,
            IntakeCancellation cancellation,
            Predicate<String> grantScope) {
        RepositoryToolContext safeContext = requireContext(context);
        return searchIndex.findSymbol(
                safeContext.snapshot(), symbol, limit, requireCancellation(cancellation),
                combinedScope(safeContext, grantScope));
    }

    /**
     * [AIREVIEW-PLAN-024] Lists every exposed snapshot path once so grant computation can run as a
     * one-shot set intersection; prefix scoping is intentionally not applied here.
     */
    public List<String> snapshotFiles(RepositoryToolContext context, IntakeCancellation cancellation) {
        RepositoryToolContext safeContext = requireContext(context);
        return searchIndex.snapshotFiles(safeContext.snapshot(), requireCancellation(cancellation));
    }

    /**
     * Reads a bounded line range from one snapshot-relative source file.
     */
    public List<SourceLine> readLines(
            RepositoryToolContext context,
            String relativePath,
            int startLine,
            int lineCount,
            IntakeCancellation cancellation) {
        RepositoryToolContext safeContext = requireContext(context);
        String safePath = requireAllowedPath(safeContext, relativePath);
        return searchIndex.readLines(
                safeContext.snapshot(),
                safePath,
                startLine,
                lineCount,
                requireCancellation(cancellation));
    }

    /**
     * Returns file metadata for one snapshot-relative source file.
     */
    public FileMetadata getFileMetadata(
            RepositoryToolContext context, String relativePath, IntakeCancellation cancellation) {
        RepositoryToolContext safeContext = requireContext(context);
        String safePath = requireAllowedPath(safeContext, relativePath);
        return searchIndex.getFileMetadata(
                safeContext.snapshot(), safePath, requireCancellation(cancellation));
    }

    private RepositoryToolContext requireContext(RepositoryToolContext context) {
        return Objects.requireNonNull(context, "context must not be null");
    }

    private Predicate<String> combinedScope(RepositoryToolContext context, Predicate<String> grantScope) {
        Predicate<String> scope = Objects.requireNonNull(grantScope, "grantScope must not be null");
        return path -> context.allows(path) && scope.test(path);
    }

    private IntakeCancellation requireCancellation(IntakeCancellation cancellation) {
        return Objects.requireNonNull(cancellation, "cancellation must not be null");
    }

    private String requireAllowedPath(RepositoryToolContext context, String relativePath) {
        String safePath = context.normalizeRelativePath(relativePath);
        if (!context.allows(relativePath)) {
            throw new IllegalArgumentException("Requested file is outside this role's assigned snapshot scope");
        }
        return safePath;
    }
}
