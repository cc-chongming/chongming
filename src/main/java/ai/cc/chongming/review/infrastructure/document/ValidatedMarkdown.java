package ai.cc.chongming.review.infrastructure.document;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Temporary, validated Markdown artifacts produced by a streaming intake pass.
 *
 * @author wangli
 */
public record ValidatedMarkdown(
        String safeFilename,
        Path rawFile,
        Path normalizedFile,
        String sourceHash,
        String contentHash,
        long sourceByteCount) {

    public ValidatedMarkdown {
        requireText(safeFilename, "safeFilename");
        Objects.requireNonNull(rawFile, "rawFile must not be null");
        Objects.requireNonNull(normalizedFile, "normalizedFile must not be null");
        requireHash(sourceHash, "sourceHash");
        requireHash(contentHash, "contentHash");
        if (sourceByteCount < 1) {
            throw new IllegalArgumentException("sourceByteCount must be positive");
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
