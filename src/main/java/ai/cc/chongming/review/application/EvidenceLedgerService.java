package ai.cc.chongming.review.application;

import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import ai.cc.chongming.review.domain.model.EvidenceBlock;
import ai.cc.chongming.review.domain.model.RepositorySnapshot;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Creates, deduplicates and batch-validates evidence derived solely from frozen repository files.
 *
 * @author wangli
 */
@Service
public class EvidenceLedgerService {

    private static final int MAX_EVIDENCE_ID_COUNT = 100;
    private static final int MAX_EVIDENCE_LINE_NUMBER = 100_000;

    private final Map<ReviewId, Ledger> ledgers = new HashMap<>();

    /**
     * Reads one frozen source line and creates or reuses its deterministic evidence block.
     *
     * @param snapshot frozen repository snapshot
     * @param request server-validated file and line reference
     * @return deduplicated immutable evidence block
     */
    public EvidenceBlock submit(RepositorySnapshot snapshot, EvidenceSubmission request) {
        return submit(snapshot, request, IntakeCancellation.neverCancelled());
    }

    /**
     * Reads one frozen source line and creates or reuses its deterministic evidence block.
     *
     * @param snapshot frozen repository snapshot
     * @param request server-validated file and line reference
     * @param cancellation cancellation signal
     * @return deduplicated immutable evidence block
     */
    public synchronized EvidenceBlock submit(
            RepositorySnapshot snapshot, EvidenceSubmission request, IntakeCancellation cancellation) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        cancellation.checkCancelled();
        Path file = resolveSnapshotFile(snapshot, request.snapshotRelativePath(), cancellation);
        String excerpt = readRequiredLine(file, request.lineNumber(), cancellation);
        String fileHash = sha256(file, cancellation);
        String excerptHash = excerptHash(snapshot.headCommit(), request.snapshotRelativePath(), request.lineNumber(), excerpt);
        Ledger ledger = ledgers.computeIfAbsent(snapshot.reviewId(), ignored -> new Ledger());
        EvidenceBlock existing = ledger.byExcerptHash.get(excerptHash);
        if (existing != null) {
            return existing;
        }
        EvidenceBlock evidence = new EvidenceBlock(
                new EvidenceId(UUID.randomUUID()),
                snapshot.reviewId(),
                snapshot.snapshotId(),
                snapshot.headCommit(),
                file.toAbsolutePath().normalize().toString(),
                request.snapshotRelativePath(),
                request.lineNumber(),
                excerpt,
                excerptHash,
                fileHash,
                Instant.now());
        ledger.byId.put(evidence.evidenceId(), evidence);
        ledger.byExcerptHash.put(excerptHash, evidence);
        return evidence;
    }

    /**
     * Bulk-loads evidence by IDs without a caller-side lookup loop.
     *
     * @param reviewId evidence owner review
     * @param evidenceIds requested evidence IDs
     * @return found evidence keyed by ID
     */
    public synchronized Map<EvidenceId, EvidenceBlock> findByIds(ReviewId reviewId, Set<EvidenceId> evidenceIds) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        requireEvidenceIds(evidenceIds);
        Ledger ledger = ledgers.get(reviewId);
        if (ledger == null) {
            return Map.of();
        }
        Map<EvidenceId, EvidenceBlock> result = new LinkedHashMap<>();
        for (EvidenceId evidenceId : new LinkedHashSet<>(evidenceIds)) {
            EvidenceBlock evidence = ledger.byId.get(evidenceId);
            if (evidence != null) {
                result.put(evidenceId, evidence);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Validates many evidence IDs by grouping them per file rather than reading each evidence independently.
     *
     * @param snapshot frozen repository snapshot used as the sole validation source
     * @param evidenceIds evidence IDs to validate
     * @return validation result for every requested ID, including forged or absent IDs
     */
    public Map<EvidenceId, EvidenceVerification> validateAll(
            RepositorySnapshot snapshot, Set<EvidenceId> evidenceIds) {
        return validateAll(snapshot, evidenceIds, IntakeCancellation.neverCancelled());
    }

    /**
     * Bulk-validates evidence while honoring cancellation and the response budget.
     *
     * @param snapshot frozen repository snapshot used as the sole validation source
     * @param evidenceIds evidence IDs to validate
     * @param cancellation cancellation signal
     * @return validation result for every requested ID, including forged or absent IDs
     */
    public synchronized Map<EvidenceId, EvidenceVerification> validateAll(
            RepositorySnapshot snapshot, Set<EvidenceId> evidenceIds, IntakeCancellation cancellation) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        requireEvidenceIds(evidenceIds);
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        cancellation.checkCancelled();
        Map<EvidenceId, EvidenceBlock> found = findByIds(snapshot.reviewId(), evidenceIds);
        Map<EvidenceId, EvidenceVerification> result = new LinkedHashMap<>();
        Map<String, List<EvidenceBlock>> byPath = new LinkedHashMap<>();
        for (EvidenceId evidenceId : evidenceIds) {
            cancellation.checkCancelled();
            EvidenceBlock evidence = found.get(evidenceId);
            if (evidence == null) {
                result.put(evidenceId, new EvidenceVerification(evidenceId, false, "EVIDENCE_NOT_FOUND"));
            } else if (!matchesSnapshot(snapshot, evidence)) {
                result.put(evidenceId, new EvidenceVerification(evidenceId, false, "SNAPSHOT_MISMATCH"));
            } else {
                byPath.computeIfAbsent(evidence.snapshotRelativePath(), ignored -> new java.util.ArrayList<>()).add(evidence);
            }
        }
        for (Map.Entry<String, List<EvidenceBlock>> entry : byPath.entrySet()) {
            cancellation.checkCancelled();
            validateFile(snapshot, entry.getKey(), entry.getValue(), result, cancellation);
        }
        return Map.copyOf(result);
    }

    private void validateFile(
            RepositorySnapshot snapshot,
            String relativePath,
            List<EvidenceBlock> evidenceForFile,
            Map<EvidenceId, EvidenceVerification> result, IntakeCancellation cancellation) {
        Path file;
        try {
            file = resolveSnapshotFile(snapshot, relativePath, cancellation);
        } catch (RepositoryAccessException exception) {
            for (EvidenceBlock evidence : evidenceForFile) {
                cancellation.checkCancelled();
                result.put(evidence.evidenceId(), new EvidenceVerification(evidence.evidenceId(), false, "FILE_UNAVAILABLE"));
            }
            return;
        }
        String currentFileHash = sha256(file, cancellation);
        Map<Integer, String> requestedLines = readRequestedLines(file, evidenceForFile, cancellation);
        for (EvidenceBlock evidence : evidenceForFile) {
            cancellation.checkCancelled();
            String line = requestedLines.get(evidence.lineNumber());
            String reason = validationFailureReason(snapshot, evidence, currentFileHash, line);
            result.put(evidence.evidenceId(), new EvidenceVerification(evidence.evidenceId(), reason == null, reason));
        }
    }

    private String validationFailureReason(
            RepositorySnapshot snapshot, EvidenceBlock evidence, String currentFileHash, String currentLine) {
        if (!evidence.fileHash().equals(currentFileHash)) {
            return "FILE_HASH_MISMATCH";
        }
        if (currentLine == null || !evidence.excerpt().equals(currentLine)) {
            return "EXCERPT_MISMATCH";
        }
        String currentExcerptHash = excerptHash(
                snapshot.headCommit(), evidence.snapshotRelativePath(), evidence.lineNumber(), currentLine);
        return evidence.excerptHash().equals(currentExcerptHash) ? null : "EXCERPT_HASH_MISMATCH";
    }

    private boolean matchesSnapshot(RepositorySnapshot snapshot, EvidenceBlock evidence) {
        return snapshot.reviewId().equals(evidence.reviewId())
                && snapshot.snapshotId().equals(evidence.repositorySnapshotId())
                && snapshot.headCommit().equals(evidence.repoRevision());
    }

    private Map<Integer, String> readRequestedLines(
            Path file, List<EvidenceBlock> evidenceForFile, IntakeCancellation cancellation) {
        Set<Integer> requested = new LinkedHashSet<>();
        for (EvidenceBlock evidence : evidenceForFile) {
            cancellation.checkCancelled();
            requested.add(evidence.lineNumber());
        }
        Map<Integer, String> result = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && result.size() < requested.size()) {
                cancellation.checkCancelled();
                lineNumber++;
                if (requested.contains(lineNumber)) {
                    result.put(lineNumber, normalizeExcerpt(line));
                }
            }
            return result;
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Evidence source file cannot be read", exception);
        }
    }

    private String readRequiredLine(Path file, int lineNumber, IntakeCancellation cancellation) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (int currentLine = 1; ; currentLine++) {
                cancellation.checkCancelled();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (currentLine == lineNumber) {
                    String normalized = normalizeExcerpt(line);
                    if (normalized.isBlank()) {
                        throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Evidence excerpt must not be blank");
                    }
                    return normalized;
                }
            }
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Evidence source line cannot be read", exception);
        }
        throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Evidence line is outside the snapshot file");
    }

    private Path resolveSnapshotFile(
            RepositorySnapshot snapshot, String relativePath, IntakeCancellation cancellation) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Evidence path is required");
        }
        Path root = requireSnapshotRoot(snapshot);
        Path requested;
        try {
            requested = Path.of(relativePath.replace('/', java.io.File.separatorChar));
        } catch (RuntimeException exception) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Evidence path is invalid", exception);
        }
        if (requested.isAbsolute()) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Evidence path must be snapshot-relative");
        }
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root)
                || Files.isSymbolicLink(resolved)
                || isReparsePoint(resolved)
                || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                || isSensitive(relativePath)
                || isBinary(resolved, cancellation)) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Evidence path is not a readable snapshot file");
        }
        return resolved;
    }

    private Path requireSnapshotRoot(RepositorySnapshot snapshot) {
        Path root = snapshot.snapshotRepositoryRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)
                || isReparsePoint(root)) {
            throw new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, "Snapshot repository root is unavailable");
        }
        return root;
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

    private void requireEvidenceIds(Set<EvidenceId> evidenceIds) {
        Objects.requireNonNull(evidenceIds, "evidenceIds must not be null");
        if (evidenceIds.size() > MAX_EVIDENCE_ID_COUNT || evidenceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Evidence ID request is outside the allowed budget");
        }
    }
    
    private String excerptHash(String revision, String relativePath, int lineNumber, String excerpt) {
        String payload = revision + relativePath + lineNumber + normalizeExcerpt(excerpt);
        return sha256(payload.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeExcerpt(String value) {
        return Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
    }

    private String sha256(Path file, IntakeCancellation cancellation) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            MessageDigest digest = messageDigest();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) {
                cancellation.checkCancelled();
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException exception) {
            throw new RepositoryAccessException(Code.SNAPSHOT_FAILED, "Evidence source file cannot be hashed", exception);
        }
    }

    private String sha256(byte[] value) {
        MessageDigest digest = messageDigest();
        return hex(digest.digest(value));
    }

    private MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    /**
     * Server-controlled request for a single line of frozen source evidence.
     *
     * @author wangli
     */
    public record EvidenceSubmission(String snapshotRelativePath, int lineNumber) {

        public EvidenceSubmission {
            if (snapshotRelativePath == null || snapshotRelativePath.isBlank()) {
                throw new IllegalArgumentException("snapshotRelativePath must not be blank");
            }
            if (lineNumber < 1 || lineNumber > MAX_EVIDENCE_LINE_NUMBER) {
                throw new IllegalArgumentException("lineNumber is outside the allowed budget");
            }
        }
    }

    /**
     * Stable evidence verification outcome suitable for future event persistence.
     *
     * @author wangli
     */
    public record EvidenceVerification(EvidenceId evidenceId, boolean valid, String reason) {

        public EvidenceVerification {
            Objects.requireNonNull(evidenceId, "evidenceId must not be null");
            if (valid && reason != null) {
                throw new IllegalArgumentException("Valid evidence must not have a rejection reason");
            }
            if (!valid && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("Invalid evidence must include a rejection reason");
            }
        }
    }

    /**
     * Per-review append-only in-memory ledger until MyBatis persistence is enabled.
     *
     * @author wangli
     */
    private static final class Ledger {

        private final Map<EvidenceId, EvidenceBlock> byId = new LinkedHashMap<>();
        private final Map<String, EvidenceBlock> byExcerptHash = new LinkedHashMap<>();
    }
}
