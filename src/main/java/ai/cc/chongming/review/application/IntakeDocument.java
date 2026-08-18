package ai.cc.chongming.review.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.web.multipart.MultipartFile;

/**
 * [AIREVIEW-PLAN-025] The requirement Markdown accepted by intake, decoupled from the transport:
 * callers may upload an {@code .md} file or type the Markdown directly; both arrive here as an
 * immutable filename + byte payload so the validator sees one uniform source.
 *
 * @author wangli
 */
public record IntakeDocument(String originalFilename, byte[] content) {

    /** Synthetic filename used when the Markdown is typed instead of uploaded. */
    public static final String MANUAL_FILENAME = "requirement.md";

    public IntakeDocument {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("originalFilename must not be blank");
        }
        Objects.requireNonNull(content, "content must not be null");
        if (content.length == 0) {
            throw new IllegalArgumentException("document content must not be empty");
        }
    }

    /**
     * Wraps typed Markdown text as a document with the synthetic filename.
     *
     * @param text non-blank Markdown source
     * @return immutable document
     */
    public static IntakeDocument ofText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("requirement text must not be blank");
        }
        return new IntakeDocument(MANUAL_FILENAME, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Resolves the uniform intake document from the two mutually exclusive transports.
     *
     * @param file uploaded Markdown part, may be {@code null}
     * @param text typed Markdown parameter, may be {@code null}
     * @return immutable document from whichever transport carried content
     * @throws ReviewIntakeException when both or neither transports carry content
     */
    public static IntakeDocument from(MultipartFile file, String text) {
        if (file != null && file.isEmpty()) {
            throw ReviewIntakeException.invalid("EMPTY_DOCUMENT", "Requirement document must not be empty");
        }
        boolean hasFile = file != null;
        boolean hasText = text != null && !text.isBlank();
        if (hasFile && hasText) {
            throw ReviewIntakeException.badRequest(
                    "INVALID_INTAKE_DOCUMENT", "需求文档只能以上传文件或手动输入其中一种方式提供");
        }
        if (hasFile) {
            try {
                return new IntakeDocument(file.getOriginalFilename(), file.getBytes());
            } catch (IOException exception) {
                throw ReviewIntakeException.badRequest(
                        "UNREADABLE_UPLOAD", "Unable to read uploaded Markdown file");
            }
        }
        if (hasText) {
            return ofText(text);
        }
        throw ReviewIntakeException.badRequest(
                "MISSING_REQUIREMENT_DOCUMENT", "请上传 Markdown 需求文档或直接输入需求内容");
    }

    /**
     * @return fresh stream over the payload for the streaming Markdown validator
     */
    public InputStream openStream() {
        return new ByteArrayInputStream(content);
    }
}
