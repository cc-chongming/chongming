package ai.cc.chongming.review.infrastructure.repository;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Performs bounded, read-only file listing, text search and line reads against frozen snapshots.
 *
 * @author wangli
 */
@Component
public class RepositorySearchIndex {

    private static final int MAX_RESULT_COUNT = 100;
    private static final int MAX_READ_LINE_COUNT = 200;
    private static final int MAX_QUERY_LENGTH = 512;

    /**
     * Lists snapshot files up to a fixed response budget without materializing the entire tree.
     *
     * @param snapshot frozen repository snapshot
     * @param limit caller-requested result cap
     * @param cancellation cancellation signal
     * @return safe file metadata
     */
    public List<FileMetadata> listFiles(RepositorySnapshot snapshot, int limit, IntakeCancellation cancellation) {
        int effectiveLimit = requireResultLimit(limit);
        Path root = requireSnapshotRoot(snapshot);
        List<FileMetadata> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> !path.equals(root))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isExposedFile(root, path, cancellation))
                    .takeWhile(path -> result.size() < effectiveLimit)
                    .forEach(path -> {
                        cancellation.checkCancelled();
                        result.add(metadata(root, path, cancellation));
                    });
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Snapshot files cannot be listed", exception);
        }
        return List.copyOf(result);
    }

    /**
     * Searches frozen text files line by line and stops once the response budget is reached.
     *
     * @param snapshot frozen repository snapshot
     * @param query plain text or regular expression query
     * @param regularExpression whether query is a regular expression
     * @param limit caller-requested result cap
     * @param cancellation cancellation signal
     * @return bounded line matches
     */
    public List<TextMatch> searchText(
            RepositorySnapshot snapshot,
            String query,
            boolean regularExpression,
            int limit,
            IntakeCancellation cancellation) {
        int effectiveLimit = requireResultLimit(limit);
        Pattern pattern = queryPattern(query, regularExpression);
        Path root = requireSnapshotRoot(snapshot);
        List<TextMatch> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            var iterator = files.filter(path -> !path.equals(root))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isExposedFile(root, path, cancellation))
                    .iterator();
            while (iterator.hasNext() && result.size() < effectiveLimit) {
                cancellation.checkCancelled();
                searchFile(root, iterator.next(), pattern, effectiveLimit, result, cancellation);
            }
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Snapshot text cannot be searched", exception);
        }
        return List.copyOf(result);
    }

    /**
     * Finds lexical symbol candidates without parsing or executing repository code.
     *
     * @param snapshot frozen repository snapshot
     * @param symbol Java-like identifier to locate
     * @param limit caller-requested result cap
     * @param cancellation cancellation signal
     * @return bounded source-line candidates
     */
    public List<TextMatch> findSymbol(
            RepositorySnapshot snapshot, String symbol, int limit, IntakeCancellation cancellation) {
        if (symbol == null || !symbol.matches("[A-Za-z_$][A-Za-z0-9_$]{0,255}")) {
            throw new IllegalArgumentException("Symbol candidate must be a bounded identifier");
        }
        return searchText(snapshot, "\\b" + Pattern.quote(symbol) + "\\b", true, limit, cancellation);
    }

    /**
     * Reads a bounded range of source lines from one safe snapshot-relative path.
     *
     * @param snapshot frozen repository snapshot
     * @param relativePath snapshot-relative file path
     * @param startLine first one-based line number
     * @param lineCount requested number of lines
     * @param cancellation cancellation signal
     * @return bounded source lines
     */
    public List<SourceLine> readLines(
            RepositorySnapshot snapshot,
            String relativePath,
            int startLine,
            int lineCount,
            IntakeCancellation cancellation) {
        if (startLine < 1 || lineCount < 1 || lineCount > MAX_READ_LINE_COUNT) {
            throw new IllegalArgumentException("Requested source-line range is outside the allowed budget");
        }
        Path file = resolveSnapshotFile(snapshot, relativePath);
        List<SourceLine> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            int endLine = startLine + lineCount - 1;
            while ((line = reader.readLine()) != null && lineNumber < endLine) {
                cancellation.checkCancelled();
                lineNumber++;
                if (lineNumber >= startLine) {
                    result.add(new SourceLine(lineNumber, line));
                }
            }
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Snapshot source lines cannot be read", exception);
        }
        return List.copyOf(result);
    }

    /**
     * Returns metadata for one safe snapshot-relative file.
     *
     * @param snapshot frozen repository snapshot
     * @param relativePath snapshot-relative file path
     * @return file metadata including its immutable SHA-256
     */
    public FileMetadata getFileMetadata(RepositorySnapshot snapshot, String relativePath) {
        return getFileMetadata(snapshot, relativePath, IntakeCancellation.neverCancelled());
    }

    /**
     * Returns file metadata for one safe snapshot-relative file while honoring cancellation.
     *
     * @param snapshot frozen repository snapshot
     * @param relativePath snapshot-relative file path
     * @param cancellation cancellation signal
     * @return file metadata including its immutable SHA-256
     */
    public FileMetadata getFileMetadata(
            RepositorySnapshot snapshot, String relativePath, IntakeCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        cancellation.checkCancelled();
        Path root = requireSnapshotRoot(snapshot);
        Path file = resolveSnapshotFile(snapshot, relativePath);
        return metadata(root, file, cancellation);
    }

    private void searchFile(
            Path root,
            Path file,
            Pattern pattern,
            int limit,
            List<TextMatch> result,
            IntakeCancellation cancellation) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while (result.size() < limit && (line = reader.readLine()) != null) {
                cancellation.checkCancelled();
                lineNumber++;
                if (pattern.matcher(line).find()) {
                    result.add(new TextMatch(toRelativePath(root, file), lineNumber, line));
                }
            }
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Snapshot source file cannot be searched", exception);
        }
    }

    private int requireResultLimit(int limit) {
        if (limit < 1 || limit > MAX_RESULT_COUNT) {
            throw new IllegalArgumentException("Requested result limit is outside the allowed budget");
        }
        return limit;
    }

    private Pattern queryPattern(String query, boolean regularExpression) {
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Search query is outside the allowed budget");
        }
        try {
            return Pattern.compile(regularExpression ? query : Pattern.quote(query));
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("Search regular expression is invalid", exception);
        }
    }

    private Path requireSnapshotRoot(RepositorySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Path root = snapshot.snapshotRepositoryRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Snapshot repository root is unavailable");
        }
        return root;
    }

    private Path resolveSnapshotFile(RepositorySnapshot snapshot, String relativePath) {
        Path root = requireSnapshotRoot(snapshot);
        if (relativePath == null || relativePath.isBlank()) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Snapshot-relative path is required");
        }
        Path requested;
        try {
            requested = Path.of(relativePath.replace('/', java.io.File.separatorChar));
        } catch (RuntimeException exception) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Snapshot-relative path is invalid", exception);
        }
        if (requested.isAbsolute()) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Absolute file paths are not allowed");
        }
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root)
                || Files.isSymbolicLink(resolved)
                || isReparsePoint(resolved)
                || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                || isSensitive(toRelativePath(root, resolved))
                || isBinary(resolved, IntakeCancellation.neverCancelled())) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Snapshot-relative path is not a readable file");
        }
        return resolved;
    }

    private boolean isExposedFile(Path root, Path file, IntakeCancellation cancellation) {
        return !Files.isSymbolicLink(file)
                && !isReparsePoint(file)
                && !isSensitive(toRelativePath(root, file))
                && !isBinary(file, cancellation);
    }

    private boolean isSensitive(String relativePath) {
        String name = Path.of(relativePath).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(".env")
                || name.startsWith(".env.")
                || name.equals("id_rsa")
                || name.endsWith(".pem")
                || name.endsWith(".key")
                || name.contains("credential")
                || name.contains("secret");
    }

    private boolean isBinary(Path file, IntakeCancellation cancellation) {
        int inspected = 0;
        int disallowedControls = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            for (int value; inspected < 8192 && (value = input.read()) != -1; inspected++) {
                cancellation.checkCancelled();
                if (value == 0) {
                    return true;
                }
                if (value < 32 && value != '\n' && value != '\r' && value != '\t' && value != '\f') {
                    disallowedControls++;
                }
            }
            return inspected > 0 && disallowedControls * 100 > inspected * 5;
        } catch (IOException exception) {
            return true;
        }
    }

    private boolean isReparsePoint(Path path) {
        try {
            Object attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            return attributes instanceof Integer value && (value & 0x400) != 0;
        } catch (IOException | UnsupportedOperationException ignored) {
            return false;
        }
    }
    private FileMetadata metadata(Path root, Path file) {
        return metadata(root, file, IntakeCancellation.neverCancelled());
    }

    private FileMetadata metadata(Path root, Path file, IntakeCancellation cancellation) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new FileMetadata(
                    toRelativePath(root, file),
                    attributes.size(),
                    attributes.lastModifiedTime().toInstant(),
                    sha256(file, cancellation),
                    languageOf(file.getFileName().toString()),
                    Files.isReadable(file));
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Snapshot file metadata cannot be read", exception);
        }
    }

    private String sha256(Path file, IntakeCancellation cancellation) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            for (int read; (read = input.read(buffer)) != -1;) {
                cancellation.checkCancelled();
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private String toRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private String languageOf(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".js") || lower.endsWith(".ts")) {
            return "javascript";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".md")) {
            return "markdown";
        }
        return "text";
    }

    /**
     * One bounded file-list response item.
     *
     * @author wangli
     */
    public record FileMetadata(
            String relativePath,
            long size,
            Instant lastModifiedAt,
            String fileHash,
            String language,
            boolean readable) {
    }

    /**
     * One bounded text-search response item.
     *
     * @author wangli
     */
    public record TextMatch(String relativePath, int lineNumber, String line) {
    }

    /**
     * One source line from a bounded read response.
     *
     * @author wangli
     */
    public record SourceLine(int lineNumber, String line) {
    }
}
