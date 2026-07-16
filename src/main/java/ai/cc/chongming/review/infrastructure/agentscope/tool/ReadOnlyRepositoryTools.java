package ai.cc.chongming.review.infrastructure.agentscope.tool;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.FileMetadata;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.SourceLine;
import ai.cc.chongming.review.infrastructure.repository.RepositorySearchIndex.TextMatch;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Server-side facade for the repository tools exposed to AgentScope role agents.
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
        return searchIndex.listFiles(requireContext(context).snapshot(), limit, requireCancellation(cancellation));
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
        return searchIndex.searchText(
                requireContext(context).snapshot(), query, regularExpression, limit, requireCancellation(cancellation));
    }

    /**
     * Finds lexical symbol candidates in the caller's frozen snapshot without invoking a compiler.
     */
    public List<TextMatch> findSymbol(
            RepositoryToolContext context, String symbol, int limit, IntakeCancellation cancellation) {
        return searchIndex.findSymbol(requireContext(context).snapshot(), symbol, limit, requireCancellation(cancellation));
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
        return searchIndex.readLines(
                requireContext(context).snapshot(),
                relativePath,
                startLine,
                lineCount,
                requireCancellation(cancellation));
    }

    /**
     * Returns file metadata for one snapshot-relative source file.
     */
    public FileMetadata getFileMetadata(
            RepositoryToolContext context, String relativePath, IntakeCancellation cancellation) {
        return searchIndex.getFileMetadata(
                requireContext(context).snapshot(), relativePath, requireCancellation(cancellation));
    }

    private RepositoryToolContext requireContext(RepositoryToolContext context) {
        return Objects.requireNonNull(context, "context must not be null");
    }

    private IntakeCancellation requireCancellation(IntakeCancellation cancellation) {
        return Objects.requireNonNull(cancellation, "cancellation must not be null");
    }
}
