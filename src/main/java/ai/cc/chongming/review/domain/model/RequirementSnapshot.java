package ai.cc.chongming.review.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, normalized representation of a submitted Markdown requirement document.
 *
 * @author wangli
 */
public record RequirementSnapshot(
        UUID snapshotId,
        ReviewTypes.ReviewId reviewId,
        int attemptNo,
        String submitter,
        String repositoryPath,
        String branch,
        String commit,
        String originalFilename,
        String sourceHash,
        String contentHash,
        String parserVersion,
        RequirementDocument document,
        Instant createdAt,
        RemoteRepositorySource remoteSource) {

    /** [AIREVIEW-PLAN-029] Legacy constructor for configured-repository intake. */
    public RequirementSnapshot(
            UUID snapshotId,
            ReviewTypes.ReviewId reviewId,
            int attemptNo,
            String submitter,
            String repositoryPath,
            String branch,
            String commit,
            String originalFilename,
            String sourceHash,
            String contentHash,
            String parserVersion,
            RequirementDocument document,
            Instant createdAt) {
        this(snapshotId, reviewId, attemptNo, submitter, repositoryPath, branch, commit, originalFilename,
                sourceHash, contentHash, parserVersion, document, createdAt, null);
    }

    public RequirementSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        requireText(submitter, "submitter");
        // [AIREVIEW-PLAN-029] A requirement-supplied online repository source replaces the
        // configured repository identity; exactly one of the two must be present.
        if (remoteSource == null) {
            requireText(repositoryPath, "repositoryPath");
        }
        requireText(originalFilename, "originalFilename");
        requireHash(sourceHash, "sourceHash");
        requireHash(contentHash, "contentHash");
        requireText(parserVersion, "parserVersion");
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * [AIREVIEW-PLAN-029] Stable repository identity for snapshot keys and references: the
     * configured repository id, or a deterministic remote identity derived from url and ref.
     */
    public String repositoryIdentity() {
        return remoteSource == null ? repositoryPath : remoteSource.repositoryIdentity();
    }

    /**
     * Deterministic Markdown structure used as the source for later evidence references.
     *
     * @author wangli
     */
    public record RequirementDocument(
            List<RequirementSection> sections,
            List<MarkdownLink> links,
            int tableCount,
            int codeBlockCount,
            boolean promptInjectionDetected) {

        public RequirementDocument {
            sections = List.copyOf(sections);
            links = List.copyOf(links);
            if (tableCount < 0 || codeBlockCount < 0) {
                throw new IllegalArgumentException("Markdown counters must not be negative");
            }
        }
    }

    /**
     * A Markdown heading and the normalized text that belongs to it.
     *
     * @author wangli
     */
    public record RequirementSection(String heading, int level, int sourceLine, String content) {

        public RequirementSection {
            requireText(heading, "heading");
            if (level < 0 || level > 6) {
                throw new IllegalArgumentException("level must be between 0 and 6");
            }
            if (sourceLine < 1) {
                throw new IllegalArgumentException("sourceLine must be positive");
            }
            content = content == null ? "" : content;
        }
    }

    /**
     * A link found in Markdown without resolving or following the target.
     *
     * @author wangli
     */
    public record MarkdownLink(String target, int sourceLine) {

        public MarkdownLink {
            requireText(target, "target");
            if (sourceLine < 1) {
                throw new IllegalArgumentException("sourceLine must be positive");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireHash(String value, String name) {
        requireText(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lower-case SHA-256 hash");
        }
    }
}
