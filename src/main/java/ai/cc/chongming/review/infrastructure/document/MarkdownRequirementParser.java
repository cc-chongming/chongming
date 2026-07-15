package ai.cc.chongming.review.infrastructure.document;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.MarkdownLink;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.RequirementDocument;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.RequirementSection;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Extracts deterministic Markdown structure without interpreting document instructions.
 *
 * @author wangli
 */
@Component
public class MarkdownRequirementParser {

    public static final String PARSER_VERSION = "markdown-line-parser-v1";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern LINK = Pattern.compile("\\[[^]]+\\]\\(([^)]+)\\)");

    /**
     * Parses normalized Markdown from disk in a line-oriented manner.
     *
     * @param normalizedMarkdown normalized UTF-8 Markdown file
     * @return extracted deterministic document structure
     */
    public RequirementDocument parse(Path normalizedMarkdown) {
        return parse(normalizedMarkdown, IntakeCancellation.neverCancelled());
    }

    /**
     * Parses normalized Markdown while checking for cancellation between individual lines.
     *
     * @param normalizedMarkdown normalized UTF-8 Markdown file
     * @param cancellation request cancellation signal
     * @return extracted deterministic document structure
     */
    public RequirementDocument parse(Path normalizedMarkdown, IntakeCancellation cancellation) {
        Objects.requireNonNull(normalizedMarkdown, "normalizedMarkdown must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        cancellation.checkCancelled();
        List<RequirementSection> sections = new ArrayList<>();
        List<MarkdownLink> links = new ArrayList<>();
        SectionBuffer current = new SectionBuffer("Document", 0, 1);
        boolean insideCodeBlock = false;
        boolean promptInjectionDetected = false;
        int tableCount = 0;
        int codeBlockCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(normalizedMarkdown, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                cancellation.checkCancelled();
                lineNumber++;
                if (isCodeFence(line)) {
                    if (!insideCodeBlock) {
                        codeBlockCount++;
                    }
                    insideCodeBlock = !insideCodeBlock;
                    current.append(line);
                    continue;
                }

                if (!insideCodeBlock) {
                    Matcher heading = HEADING.matcher(line);
                    if (heading.matches()) {
                        sections.add(current.toSection());
                        current = new SectionBuffer(heading.group(2), heading.group(1).length(), lineNumber);
                        continue;
                    }
                    if (isTableRow(line)) {
                        tableCount++;
                    }
                    Matcher linksInLine = LINK.matcher(line);
                    while (linksInLine.find()) {
                        links.add(new MarkdownLink(linksInLine.group(1), lineNumber));
                    }
                    promptInjectionDetected |= containsPromptInjectionMarker(line);
                }
                current.append(line);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse normalized Markdown", exception);
        }
        cancellation.checkCancelled();
        sections.add(current.toSection());
        return new RequirementDocument(sections, links, tableCount, codeBlockCount, promptInjectionDetected);
    }

    private boolean isCodeFence(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private boolean isTableRow(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    private boolean containsPromptInjectionMarker(String line) {
        String normalized = line.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("ignore previous")
                || normalized.contains("ignore all previous")
                || normalized.contains("system prompt")
                || normalized.contains("developer message")
                || normalized.contains("\u5ffd\u7565\u4e4b\u524d\u6307\u4ee4")
                || normalized.contains("\u7cfb\u7edf\u63d0\u793a");
    }

    /**
     * Accumulates lines for a section without retaining a second copy of the entire document.
     *
     * @author wangli
     */
    private static final class SectionBuffer {

        private final String heading;
        private final int level;
        private final int sourceLine;
        private final StringBuilder content = new StringBuilder();

        private SectionBuffer(String heading, int level, int sourceLine) {
            this.heading = heading;
            this.level = level;
            this.sourceLine = sourceLine;
        }

        private void append(String line) {
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(line);
        }

        private RequirementSection toSection() {
            return new RequirementSection(heading, level, sourceLine, content.toString());
        }
    }
}