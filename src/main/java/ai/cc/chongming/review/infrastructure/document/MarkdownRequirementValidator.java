package ai.cc.chongming.review.infrastructure.document;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.ReviewIntakeException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import org.springframework.stereotype.Component;

/**
 * Performs streaming Markdown filename, UTF-8 and normalized-hash validation.
 *
 * @author wangli
 */
@Component
public class MarkdownRequirementValidator {

    /**
     * Streams a submitted document into temporary raw and normalized files.
     *
     * @param originalFilename client-submitted filename
     * @param content input stream supplied by the multipart request
     * @return temporary validated artifacts; caller must discard them after storing
     */
    public ValidatedMarkdown validate(String originalFilename, InputStream content) {
        return validate(originalFilename, content, IntakeCancellation.neverCancelled());
    }

    /**
     * Streams a submitted document into temporary raw and normalized files with cancellation checks.
     *
     * @param originalFilename client-submitted filename
     * @param content input stream supplied by the multipart request
     * @param cancellation cooperative cancellation token
     * @return temporary validated artifacts; caller must discard them after storing
     */
    public ValidatedMarkdown validate(
            String originalFilename, InputStream content, IntakeCancellation cancellation) {
        cancellation.checkCancelled();
        String safeFilename = validateFilename(originalFilename);
        Path stagingDirectory = createStagingDirectory();
        Path rawFile = stagingDirectory.resolve("source.md");
        Path normalizedFile = stagingDirectory.resolve("normalized.md");
        try {
            long sourceByteCount = copyRawAndHash(content, rawFile, cancellation);
            if (sourceByteCount == 0) {
                throw ReviewIntakeException.invalid("EMPTY_MARKDOWN", "Markdown file must not be empty");
            }
            String contentHash = normalizeAndHash(rawFile, normalizedFile, cancellation);
            String sourceHash = sha256(rawFile, cancellation);
            return new ValidatedMarkdown(
                    safeFilename, rawFile, normalizedFile, sourceHash, contentHash, sourceByteCount);
        } catch (ReviewIntakeException exception) {
            deleteStagingDirectory(stagingDirectory);
            throw exception;
        } catch (CharacterCodingException exception) {
            deleteStagingDirectory(stagingDirectory);
            throw ReviewIntakeException.invalid("INVALID_UTF8", "Markdown content must be valid UTF-8 text");
        } catch (IOException exception) {
            deleteStagingDirectory(stagingDirectory);
            throw new IllegalStateException("Failed to stage Markdown requirement", exception);
        }
    }
    /**
     * Deletes temporary validation artifacts after they have been stored or rejected.
     *
     * @param markdown temporary validation result
     */
    public void discard(ValidatedMarkdown markdown) {
        deleteStagingDirectory(markdown.rawFile().getParent());
    }

    private long copyRawAndHash(InputStream content, Path rawFile, IntakeCancellation cancellation) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        try (InputStream input = content;
                DigestOutputStream output = new DigestOutputStream(
                        Files.newOutputStream(rawFile), newDigest())) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancellation.checkCancelled();

                for (int index = 0; index < read; index++) {
                    if (buffer[index] == 0) {
                        throw ReviewIntakeException.invalid("BINARY_MARKDOWN", "Markdown content must not contain NUL bytes");
                    }
                }
                output.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private String normalizeAndHash(Path rawFile, Path normalizedFile, IntakeCancellation cancellation)
            throws IOException, CharacterCodingException {
        MessageDigest contentDigest = newDigest();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        Files.newInputStream(rawFile),
                        StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)));
                DigestOutputStream output = new DigestOutputStream(Files.newOutputStream(normalizedFile), contentDigest);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                cancellation.checkCancelled();

                lineNumber++;
                validateTextLine(line, lineNumber);
                if (lineNumber > 1) {
                    writer.write('\n');
                }
                writer.write(Normalizer.normalize(line, Normalizer.Form.NFC));
            }
        }
        return toHex(contentDigest.digest());
    }

    private String sha256(Path path, IntakeCancellation cancellation) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancellation.checkCancelled();

                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private String validateFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw ReviewIntakeException.invalid("MISSING_FILENAME", "A Markdown filename is required");
        }
        if (originalFilename.contains("..")
                || originalFilename.contains("/")
                || originalFilename.contains("\\")
                || originalFilename.startsWith("~")) {
            throw ReviewIntakeException.invalid("UNSAFE_FILENAME", "Markdown filename must not contain a path");
        }
        if (!originalFilename.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            throw ReviewIntakeException.invalid("UNSUPPORTED_FILE_TYPE", "Only .md files are supported");
        }
        return originalFilename;
    }

    private void validateTextLine(String line, int lineNumber) {
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (Character.isISOControl(character) && character != '\t') {
                throw ReviewIntakeException.invalid(
                        "BINARY_MARKDOWN", "Markdown contains a binary control character at line " + lineNumber);
            }
        }
    }

    private Path createStagingDirectory() {
        try {
            return Files.createTempDirectory("chongming-requirement-");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create Markdown staging directory", exception);
        }
    }

    private void deleteStagingDirectory(Path stagingDirectory) {
        if (stagingDirectory == null || !Files.exists(stagingDirectory)) {
            return;
        }
        try (var files = Files.walk(stagingDirectory)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the OS temp directory remains the fallback owner.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup; validation results are unusable once this method returns.
        }
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
