package ai.cc.chongming.review.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.application.ReviewIntakeException;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementValidator;
import ai.cc.chongming.review.infrastructure.document.ValidatedMarkdown;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/**
 * Tests streaming Markdown filename, encoding and normalized hash validation.
 *
 * @author wangli
 */
class MarkdownRequirementValidatorTests {

    private final MarkdownRequirementValidator validator = new MarkdownRequirementValidator();

    @Test
    void normalizesLineEndingsAndUnicodeWhilePreservingSourceHash() throws Exception {
        byte[] raw = "# Scope\r\nCafe\u0301".getBytes(StandardCharsets.UTF_8);

        ValidatedMarkdown markdown = validator.validate("requirements.md", new ByteArrayInputStream(raw));
        try {
            assertThat(markdown.sourceHash()).isEqualTo(sha256(raw));
            assertThat(Files.readString(markdown.normalizedFile()))
                    .isEqualTo("# Scope\nCafé");
            assertThat(markdown.contentHash())
                    .isEqualTo(sha256("# Scope\nCafé".getBytes(StandardCharsets.UTF_8)));
        } finally {
            validator.discard(markdown);
        }
    }

    @Test
    void rejectsAnEmptyMarkdownDocument() {
        assertThatThrownBy(() -> validator.validate("requirements.md", stream("")))
                .isInstanceOf(ReviewIntakeException.class)
                .extracting(exception -> ((ReviewIntakeException) exception).code())
                .isEqualTo("EMPTY_MARKDOWN");
    }
    @Test
    void acceptsAOneMegabyteSingleLineThroughTheStreamingPipeline() throws Exception {
        String content = "# Scope\n" + "x".repeat(1_000_000);
        ValidatedMarkdown markdown = validator.validate("requirements.md", stream(content));
        try {
            assertThat(Files.size(markdown.normalizedFile())).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
            assertThat(markdown.contentHash()).isEqualTo(sha256(content.getBytes(StandardCharsets.UTF_8)));
        } finally {
            validator.discard(markdown);
        }
    }

    @Test
    void rejectsAFileWithANonMarkdownExtension() {
        assertThatThrownBy(() -> validator.validate("requirements.pdf", stream("# Requirement")))
                .isInstanceOf(ReviewIntakeException.class)
                .extracting(exception -> ((ReviewIntakeException) exception).code())
                .isEqualTo("UNSUPPORTED_FILE_TYPE");
    }
    @Test
    void rejectsUnsafeFilenameBeforeUsingItAsAPath() {
        assertThatThrownBy(() -> validator.validate("../requirements.md", stream("# Requirement")))
                .isInstanceOf(ReviewIntakeException.class)
                .extracting(exception -> ((ReviewIntakeException) exception).code())
                .isEqualTo("UNSAFE_FILENAME");
    }

    @Test
    void rejectsInvalidUtf8AndNulBytes() {
        assertThatThrownBy(() -> validator.validate("requirements.md", new ByteArrayInputStream(new byte[] {(byte) 0xC3, 0x28})))
                .isInstanceOf(ReviewIntakeException.class)
                .extracting(exception -> ((ReviewIntakeException) exception).code())
                .isEqualTo("INVALID_UTF8");
        assertThatThrownBy(() -> validator.validate("requirements.md", new ByteArrayInputStream(new byte[] {'#', 0})))
                .isInstanceOf(ReviewIntakeException.class)
                .extracting(exception -> ((ReviewIntakeException) exception).code())
                .isEqualTo("BINARY_MARKDOWN");
    }

    @Test
    void rejectsCancelledStreamingValidationBeforePublishingStagingFiles() {
        assertThatThrownBy(() -> validator.validate("requirements.md", stream("# Requirement"), () -> true))
                .isInstanceOf(ReviewIntakeException.class)
                .extracting(exception -> ((ReviewIntakeException) exception).code())
                .isEqualTo("INTAKE_CANCELLED");
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte element : digest) {
            result.append(String.format("%02x", element));
        }
        return result.toString();
    }
}