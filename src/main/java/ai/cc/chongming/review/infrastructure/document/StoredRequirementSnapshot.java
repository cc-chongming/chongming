package ai.cc.chongming.review.infrastructure.document;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Controlled workspace locations for an immutable requirement snapshot.
 *
 * @author wangli
 */
public record StoredRequirementSnapshot(Path rawMarkdownPath, Path normalizedMarkdownPath, Path manifestPath) {

    public StoredRequirementSnapshot {
        Objects.requireNonNull(rawMarkdownPath, "rawMarkdownPath must not be null");
        Objects.requireNonNull(normalizedMarkdownPath, "normalizedMarkdownPath must not be null");
        Objects.requireNonNull(manifestPath, "manifestPath must not be null");
    }
}
